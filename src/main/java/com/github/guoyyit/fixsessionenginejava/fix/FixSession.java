package com.github.guoyyit.fixsessionenginejava.fix;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A FIX 4.4 session endpoint, usable as initiator or acceptor.
 *
 * <p>All protocol state is touched only on the supplied {@link TaskRunner}'s
 * single thread; a dedicated daemon reader thread hands inbound raw messages
 * to that thread. Outbound application messages are buffered in a bounded
 * queue, so {@link #send} never blocks on a slow inbound consumer or on the
 * network.
 */
public final class FixSession implements AutoCloseable {

    static final String RESET_REQUEST = "ZZ01";
    static final String RESET_CONFIRM = "ZZ02";

    private static final Set<String> REPLAYABLE_EXCLUDE = Set.of(
            MsgType.HEARTBEAT, MsgType.TEST_REQUEST, MsgType.RESEND_REQUEST,
            MsgType.REJECT, MsgType.SEQUENCE_RESET, MsgType.LOGOUT, MsgType.LOGON,
            RESET_REQUEST, RESET_CONFIRM);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final SessionId sessionId;
    private final SessionRole role;
    private final SessionConfig config;
    private final Application application;
    private final MessageStore store;
    private final Transport transport;
    private final TaskRunner taskRunner;
    private final Clock clock;

    private volatile SessionState state;

    private final LinkedBlockingQueue<FixMessage> outboundQueue;
    private final TreeMap<Long, FixMessage> reorderBuffer = new TreeMap<>();
    private final ArrayDeque<Object> timerHandles = new ArrayDeque<>();

    private long resendBegin;
    private int resendAttempts;
    private Object resendTimer;

    private long resetTriggerSeq;
    private int resetAttempts;
    private Object resetTimer;

    private long lastOutboundActivityMillis;
    private long lastInboundActivityMillis;
    private String pendingTestReqId;
    private int testReqCounter;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch logoutAckLatch = new CountDownLatch(1);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    public FixSession(SessionId sessionId,
                      SessionRole role,
                      SessionConfig config,
                      Application application,
                      MessageStore store,
                      Transport transport) {
        this(sessionId, role, config, application, store, transport,
                new ThreadedTaskRunner(), Clock.systemUTC());
    }

    public FixSession(SessionId sessionId,
                      SessionRole role,
                      SessionConfig config,
                      Application application,
                      MessageStore store,
                      Transport transport,
                      TaskRunner taskRunner,
                      Clock clock) {
        this.sessionId = sessionId;
        this.role = role;
        this.config = config;
        this.application = application;
        this.store = store;
        this.transport = transport;
        this.taskRunner = taskRunner;
        this.clock = clock;
        this.outboundQueue = new LinkedBlockingQueue<>(config.getMaxOutboundQueueSize());
        this.state = SessionState.DISCONNECTED;
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public SessionState getState() {
        return state;
    }

    public long getNextSenderSeqNum() {
        return store.getNextSenderSeqNum();
    }

    public long getNextTargetSeqNum() {
        return store.getNextTargetSeqNum();
    }

    /**
     * Initiator: verifies the store and sends Logon. Acceptor: verifies the
     * store and starts reading, waiting for the peer Logon.
     *
     * @throws FixSessionException.Unrecoverable when persisted state cannot be
     *         restored, including a crash during an unconfirmed sequence reset.
     */
    public void start() throws FixSessionException {
        store.verify();
        if (store.isResetPending()) {
            throw new FixSessionException.Unrecoverable("session " + sessionId
                    + " crashed while a local sequence reset was unconfirmed; the peer "
                    + "sequence view may differ, refusing to continue automatically");
        }
        long now = clock.millis();
        lastInboundActivityMillis = now;
        lastOutboundActivityMillis = now;
        startReader();
        scheduleHeartbeatTick();
        if (role == SessionRole.INITIATOR) {
            state = SessionState.LOGON_SENT;
            sendLogon(false);
        } else {
            state = SessionState.LOGON_RECEIVED;
        }
    }

    /**
     * Enqueue an application message for reliable, ordered delivery.
     *
     * @throws FixSessionException.QueueOverflow when the bounded outbound
     *         queue already holds {@code maxOutboundQueueSize} messages; the
     *         caller may retry. No sequence number is assigned in that case.
     * @throws FixSessionException.IllegalTransition unless the session is
     *         ACTIVE or catching up gaps (RESYNC).
     */
    public void send(FixMessage message) throws FixSessionException {
        if (message == null || message.getMsgType() == null) {
            throw new IllegalArgumentException("message with MsgType(35) required");
        }
        SessionState current = state;
        if (current != SessionState.ACTIVE && current != SessionState.RESYNC) {
            throw new FixSessionException.IllegalTransition(
                    "cannot send application message in state " + current);
        }
        if (!outboundQueue.offer(message)) {
            throw new FixSessionException.QueueOverflow("outbound queue capacity "
                    + config.getMaxOutboundQueueSize() + " reached for session " + sessionId);
        }
        taskRunner.execute(this::pumpOutbound);
    }

    /** Begin the negotiated mid-session sequence reset and wait for completion. */
    public void resetSequenceNumbers() throws FixSessionException {
        resetSequenceNumbers(config.getResetTimeout());
    }

    public void resetSequenceNumbers(Duration timeout) throws FixSessionException {
        proposeReset();
        try {
            if (!awaitState(timeout, SessionState.ACTIVE)) {
                throw new FixSessionException("sequence reset not completed within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FixSessionException("interrupted while waiting for sequence reset", e);
        }
    }

    /** Send Logout(5) and wait up to one heartbeat interval for acknowledgement. */
    public void logout() throws FixSessionException {
        logout(config.getHeartBtInt());
    }

    public void logout(Duration timeout) throws FixSessionException {
        taskRunner.execute(() -> {
            if (state == SessionState.DISCONNECTED
                    || state == SessionState.LOGOUT_SENT
                    || state == SessionState.LOGOUT_RECEIVED) {
                return;
            }
            transition(SessionState.LOGOUT_SENT);
            sendAdmin(MsgType.LOGOUT, null);
        });
        try {
            if (!logoutAckLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                disconnect("logout acknowledgement timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FixSessionException("interrupted while waiting for logout", e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            taskRunner.execute(this::teardown);
        }
    }

    boolean awaitState(Duration timeout, SessionState wanted) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (state != wanted) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    private void proposeReset() throws FixSessionException {
        SessionState current = state;
        if (current == SessionState.RESET_REQUEST_SENT
                || current == SessionState.RESET_PENDING_PEER) {
            throw new FixSessionException.IllegalTransition("reset already in progress");
        }
        if (current != SessionState.ACTIVE) {
            throw new FixSessionException.IllegalTransition(
                    "cannot request sequence reset in state " + current);
        }
        taskRunner.execute(() -> {
            if (state != SessionState.ACTIVE) {
                return;
            }
            drainOutboundQueue();
            resetAttempts = 1;
            resetTriggerSeq = store.getNextSenderSeqNum();
            transition(SessionState.RESET_REQUEST_SENT);
            sendWithNextSeq(newAdmin(RESET_REQUEST));
            resetTimer = taskRunner.schedule(this::onResetTimeout, config.getResetTimeout());
        });
    }

    // ---- inbound --------------------------------------------------------

    private void startReader() {
        if (!(transport instanceof LoopbackTransport)) {
            throw new IllegalStateException("only LoopbackTransport is supported");
        }
        pollHandle = taskRunner.schedule(this::pollInbound, POLL_INTERVAL);
    }

    private Object pollHandle;

    private void pollInbound() {
        if (state == SessionState.DISCONNECTED || closed.get()) {
            return;
        }
        LoopbackTransport loopback = (LoopbackTransport) transport;
        try {
            while (true) {
                String raw = loopback.receive(0, TimeUnit.MILLISECONDS);
                onRawMessage(raw);
            }
        } catch (TimeoutException noneAvailable) {
            pollHandle = taskRunner.schedule(this::pollInbound, POLL_INTERVAL);
        } catch (Exception e) {
            if (!closed.get()) {
                disconnect("transport closed: " + e.getMessage());
            }
        }
    }

    private void onRawMessage(String raw) {
        if (state == SessionState.DISCONNECTED) {
            return;
        }
        if (Boolean.getBoolean("fix.trace")) {
            System.out.println(role + "(" + sessionId.getSenderCompId()
                    + ") state=" + state + " RAW=" + raw.replace('\u0001', '|'));
        }
        FixMessage message;
        try {
            message = FixMessage.decode(raw);
        } catch (FixParseException e) {
            handleParseFailure(e);
            return;
        }
        lastInboundActivityMillis = clock.millis();
        long seqNum = message.getSeqNum();
        long expected = store.getNextTargetSeqNum();

        boolean gapFill = MsgType.SEQUENCE_RESET.equals(message.getMsgType())
                && "Y".equals(message.getString(FixTags.GAP_FILL_FLAG));
        if (state == SessionState.RESET_PENDING_PEER
                && MsgType.HEARTBEAT.equals(message.getMsgType())) {
            onPeerResetSignal(raw, message);
            return;
        }
        if (state == SessionState.ACTIVE && seqNum == 1
                && MsgType.HEARTBEAT.equals(message.getMsgType())
                && isPossDup(message)) {
            return;
        }
        if (!gapFill && !validateEnvelope(message)) {
            doLogoutAndDisconnect("session envelope mismatch (BeginString/CompID)");
            return;
        }

        if (seqNum < expected) {
            if (state == SessionState.RESET_REQUEST_SENT
                    && RESET_CONFIRM.equals(message.getMsgType())) {
                processInOrder(raw, message);
                return;
            }
            if ((state == SessionState.RESET_REQUEST_RECEIVED
                    || state == SessionState.RESET_PENDING_PEER) && seqNum == 1) {
                onPeerResetSignal(raw, message);
            } else {
                handleOldSequence(message);
            }
        } else if (seqNum > expected) {
            if (state == SessionState.RESET_REQUEST_RECEIVED
                    && MsgType.HEARTBEAT.equals(message.getMsgType())) {
                onPeerResetSignal(raw, message);
            } else {
                bufferAndRequestResend(message, seqNum);
            }
        } else {
            processInOrder(raw, message);
        }
    }

    private void processInOrder(String raw, FixMessage message) {
        long seqNum = message.getSeqNum();
        reorderBuffer.remove(seqNum);
        if (!isPossDup(message)) {
            store.saveIncoming(seqNum, raw);
        }
        boolean holdsTarget = dispatch(message);
        if (!holdsTarget) {
            store.setNextTargetSeqNum(seqNum + 1);
        }
        pumpReorderBuffer();
    }

    /** Returns true when the message intentionally holds the target pointer. */
    private boolean dispatch(FixMessage message) {
        String type = message.getMsgType();
        switch (type) {
            case MsgType.HEARTBEAT -> {
                pendingTestReqId = null;
                return state == SessionState.RESET_PENDING_PEER;
            }
            case MsgType.TEST_REQUEST -> {
                String testReqId = message.getString(FixTags.TEST_REQ_ID);
                FixMessage heartbeat = newAdmin(MsgType.HEARTBEAT);
                heartbeat.addField(FixTags.TEST_REQ_ID, testReqId);
                sendWithNextSeq(heartbeat);
            }
            case MsgType.RESEND_REQUEST -> {
                if (state == SessionState.LOGON_SENT || state == SessionState.LOGON_RECEIVED) {
                    return false;
                }
                handleResendRequest(message);
            }
            case MsgType.REJECT -> application.onError(sessionId,
                    "session reject from peer: " + message.getString(FixTags.TEXT));
            case MsgType.SEQUENCE_RESET -> {
                return handleSequenceReset(message);
            }
            case MsgType.LOGON -> handleLogon(message);
            case MsgType.LOGOUT -> handleLogout();
            case RESET_REQUEST -> handleResetRequest(message);
            case RESET_CONFIRM -> {
                return handleResetConfirm(message);
            }
            default -> handleApplicationMessage(message);
        }
        return false;
    }

    private void handleApplicationMessage(FixMessage message) {
        String type = message.getMsgType();
        if (!config.getApplicationMsgTypes().contains(type)) {
            rejectOrDisconnect(message, "unknown MsgType " + type, FixTags.MSG_TYPE, 11);
            return;
        }
        if (!hasRequiredSessionFields(message)) {
            return;
        }
        if (isPossDup(message) && message.getSeqNum() < store.getNextTargetSeqNum()) {
            return;
        }
        application.fromApp(sessionId, message);
    }

    private void handleOldSequence(FixMessage message) {
        if (isPossDup(message)) {
            return;
        }
        doLogoutAndDisconnect("received sequence " + message.getSeqNum()
                + " below expected " + store.getNextTargetSeqNum() + " without PossDupFlag");
    }

    private void bufferAndRequestResend(FixMessage message, long seqNum) {
        reorderBuffer.put(seqNum, message);
        if (resendTimer == null) {
            resendBegin = store.getNextTargetSeqNum();
            resendAttempts = 1;
            reorderBuffer.clear();
            reorderBuffer.put(seqNum, message);
            sendResendRequest(resendBegin, 0);
            resendTimer = taskRunner.schedule(this::onResendTimeout,
                    config.getResendTimeout());
        }
    }

    private void pumpReorderBuffer() {
        long expected = store.getNextTargetSeqNum();
        while (!reorderBuffer.isEmpty() && reorderBuffer.firstKey() < expected) {
            reorderBuffer.pollFirstEntry();
        }
        while (reorderBuffer.containsKey(expected)) {
            FixMessage buffered = reorderBuffer.remove(expected);
            if (isPossDup(buffered)) {
                store.setNextTargetSeqNum(expected + 1);
            } else {
                processInOrder(encodeForRecovery(buffered), buffered);
            }
            expected = store.getNextTargetSeqNum();
        }
        if (resendTimer != null && reorderBuffer.isEmpty()) {
            taskRunner.cancel(resendTimer);
            resendTimer = null;
            if (state == SessionState.RESYNC) {
                reachActiveIfCaughtUp();
            }
        }
    }

    private String encodeForRecovery(FixMessage message) {
        return message.encode(config.getBeginString());
    }

    private void onResendTimeout() {
        resendTimer = null;
        if (reorderBuffer.isEmpty()) {
            return;
        }
        if (resendAttempts >= config.getMaxResendAttempts()) {
            disconnect("gap from sequence " + resendBegin + " not filled after "
                    + resendAttempts + " resend requests");
            return;
        }
        resendAttempts++;
        sendResendRequest(resendBegin, 0);
        resendTimer = taskRunner.schedule(this::onResendTimeout, config.getResendTimeout());
    }

    // ---- validation and rejection --------------------------------------

    private void handleParseFailure(FixParseException error) {
        if (error.getRefTagId() != null && error.getRefTagId() == FixTags.CHECK_SUM) {
            disconnect("inbound checksum error: " + error.getMessage());
            return;
        }
        RejectPolicy policy = config.getRejectPolicy();
        if (policy == RejectPolicy.IGNORE) {
            application.onError(sessionId, "ignored malformed message: " + error.getMessage());
            return;
        }
        if (policy == RejectPolicy.DISCONNECT) {
            doLogoutAndDisconnect("malformed message: " + error.getMessage());
            return;
        }
        sendSessionReject(0, error.getRefTagId(), null,
                error.getRejectReason() == null ? 0 : error.getRejectReason(),
                error.getMessage());
    }

    private void rejectOrDisconnect(FixMessage offending, String text, int refTag, int reason) {
        RejectPolicy policy = config.getRejectPolicy();
        if (policy == RejectPolicy.IGNORE) {
            application.onError(sessionId, text);
        } else if (policy == RejectPolicy.DISCONNECT) {
            doLogoutAndDisconnect(text);
        } else {
            sendSessionReject(offending.getSeqNum(), refTag, offending.getMsgType(), reason, text);
        }
    }

    private void sendSessionReject(long refSeqNum, Integer refTag, String refMsgType,
                                   int reason, String text) {
        FixMessage reject = newAdmin(MsgType.REJECT);
        reject.addField(FixTags.REF_SEQ_NUM, String.valueOf(refSeqNum));
        if (refTag != null) {
            reject.addField(FixTags.REF_TAG_ID, String.valueOf(refTag));
        }
        if (refMsgType != null) {
            reject.addField(FixTags.REF_MSG_TYPE, refMsgType);
        }
        reject.addField(FixTags.SESSION_REJECT_REASON, String.valueOf(reason));
        reject.addField(FixTags.TEXT, abbreviate(text));
        sendWithNextSeq(reject);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private boolean validateEnvelope(FixMessage message) {
        return hasRequiredSessionFields(message)
                && sessionId.getTargetCompId().equals(message.getString(FixTags.SENDER_COMP_ID))
                && sessionId.getSenderCompId().equals(message.getString(FixTags.TARGET_COMP_ID));
    }

    private boolean hasRequiredSessionFields(FixMessage message) {
        int[] required = {FixTags.MSG_TYPE, FixTags.SENDER_COMP_ID, FixTags.TARGET_COMP_ID,
                FixTags.MSG_SEQ_NUM, FixTags.SENDING_TIME};
        for (int tag : required) {
            if (!message.has(tag)) {
                rejectOrDisconnect(message, "missing required tag " + tag, tag, 1);
                return false;
            }
        }
        return true;
    }

    private boolean isExpectedRequired(FixMessage message, int tag) {
        if (message.has(tag)) {
            return true;
        }
        rejectOrDisconnect(message, "missing required tag " + tag, tag, 1);
        return false;
    }

    private static boolean isPossDup(FixMessage message) {
        return "Y".equals(message.getString(FixTags.POSS_DUP_FLAG));
    }

    // ---- resend request / replay ---------------------------------------

    private void handleResendRequest(FixMessage message) {
        if (state != SessionState.ACTIVE && state != SessionState.RESYNC
                && state != SessionState.RESET_PENDING_PEER) {
            rejectOrDisconnect(message, "ResendRequest illegal in state " + state,
                    FixTags.MSG_TYPE, 2);
            return;
        }
        long beginSeq;
        long endSeq;
        try {
            beginSeq = message.getLong(FixTags.BEGIN_SEQ_NO);
            endSeq = message.getLong(FixTags.END_SEQ_NO);
        } catch (FixParseException e) {
            rejectOrDisconnect(message, e.getMessage(), e.getRefTagId(), 1);
            return;
        }
        if (beginSeq < 1 || (endSeq != 0 && endSeq < beginSeq)) {
            rejectOrDisconnect(message, "invalid ResendRequest range "
                    + beginSeq + ".." + endSeq, FixTags.BEGIN_SEQ_NO, 5);
            return;
        }
        long upperExclusive = endSeq == 0 ? store.getNextSenderSeqNum() : endSeq + 1;
        long gapFillStart = beginSeq;
        for (long seq = beginSeq; seq < upperExclusive; seq++) {
            FixMessage stored = loadStoredOutgoing(seq);
            if (stored == null) {
                continue;
            }
            if (REPLAYABLE_EXCLUDE.contains(stored.getMsgType())) {
                continue;
            }
            if (gapFillStart < seq) {
                sendGapFill(gapFillStart, seq);
            }
            sendReplay(stored, seq);
            gapFillStart = seq + 1;
        }
        if (gapFillStart < upperExclusive) {
            sendGapFill(gapFillStart, upperExclusive);
        }
    }

    private FixMessage loadStoredOutgoing(long seq) {
        java.util.List<String> rows = store.loadOutgoing(seq, seq + 1);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return FixMessage.decode(rows.get(0));
        } catch (FixParseException e) {
            application.onError(sessionId, "stored message at " + seq + " undecodable");
            return null;
        }
    }

    private void sendReplay(FixMessage original, long seqNum) {
        FixMessage replay = new FixMessage();
        for (FixField field : original.getFields()) {
            if (field.getTag() != FixTags.POSS_DUP_FLAG
                    && field.getTag() != FixTags.ORIG_SENDING_TIME
                    && field.getTag() != FixTags.SENDER_COMP_ID
                    && field.getTag() != FixTags.TARGET_COMP_ID
                    && field.getTag() != FixTags.SENDING_TIME) {
                replay.addField(field.getTag(), field.getValue());
            }
        }
        withEnvelope(replay);
        replay.addField(FixTags.POSS_DUP_FLAG, "Y");
        replay.addField(FixTags.ORIG_SENDING_TIME,
                original.getString(FixTags.SENDING_TIME));
        sendRawWithSeq(seqNum, replay.encode(config.getBeginString()));
    }

    private void sendGapFill(long beginSeq, long newSeq) {
        FixMessage gapFill = newAdmin(MsgType.SEQUENCE_RESET);
        gapFill.addField(FixTags.GAP_FILL_FLAG, "Y");
        gapFill.addField(FixTags.MSG_SEQ_NUM, String.valueOf(beginSeq));
        gapFill.addField(FixTags.NEW_SEQ_NO, String.valueOf(newSeq));
        String raw = gapFill.encode(config.getBeginString());
        transportSend(raw);
        lastOutboundActivityMillis = clock.millis();
    }

    private void sendResendRequest(long beginSeq, long endSeq) {
        FixMessage request = newAdmin(MsgType.RESEND_REQUEST);
        request.addField(FixTags.BEGIN_SEQ_NO, String.valueOf(beginSeq));
        request.addField(FixTags.END_SEQ_NO, String.valueOf(endSeq));
        sendWithNextSeq(request);
    }

    // ---- logon / logout / sequence reset -------------------------------

    private void sendLogon(boolean resetSeqNumFlag) {
        FixMessage logon = newAdmin(MsgType.LOGON);
        logon.addField(FixTags.HEART_BT_INT,
                String.valueOf(config.getHeartBtInt().getSeconds()));
        if (resetSeqNumFlag) {
            logon.addField(FixTags.RESET_SEQ_NUM_FLAG, "Y");
        }
        sendWithNextSeq(logon);
    }

    private void handleLogon(FixMessage message) {
        SessionState current = state;
        if (!isExpectedRequired(message, FixTags.HEART_BT_INT)) {
            return;
        }
        boolean resetFlag = "Y".equals(message.getString(FixTags.RESET_SEQ_NUM_FLAG));
        if (current == SessionState.LOGON_RECEIVED) {
            if (resetFlag) {
                store.resetSequenceNumbers(1, 1);
            }
            transition(SessionState.RESYNC);
            sendLogon(resetFlag);
            reachActiveIfCaughtUp();
        } else if (current == SessionState.LOGON_SENT) {
            transition(SessionState.RESYNC);
            reachActiveIfCaughtUp();
        } else {
            rejectOrDisconnect(message, "Logon illegal in state " + current,
                    FixTags.MSG_TYPE, 2);
        }
    }

    private void reachActiveIfCaughtUp() {
        if (state == SessionState.RESYNC && reorderBuffer.isEmpty()) {
            transition(SessionState.ACTIVE);
            application.onLogon(sessionId);
            pumpOutbound();
        }
    }

    private void handleLogout() {
        if (state == SessionState.LOGOUT_SENT) {
            logoutAckLatch.countDown();
            teardown();
            return;
        }
        if (state == SessionState.LOGOUT_RECEIVED || state == SessionState.DISCONNECTED) {
            return;
        }
        transition(SessionState.LOGOUT_RECEIVED);
        sendAdmin(MsgType.LOGOUT, null);
        logoutAckLatch.countDown();
        taskRunner.schedule(this::teardown, Duration.ofMillis(100));
    }

    private boolean handleSequenceReset(FixMessage message) {
        long seqNum = message.getSeqNum();
        long newSeqNo;
        try {
            newSeqNo = message.getLong(FixTags.NEW_SEQ_NO);
        } catch (FixParseException e) {
            rejectOrDisconnect(message, e.getMessage(), e.getRefTagId(), 1);
            return false;
        }
        boolean gapFill = "Y".equals(message.getString(FixTags.GAP_FILL_FLAG));
        if (!gapFill) {
            rejectOrDisconnect(message,
                    "SequenceReset-Reset outside negotiated reset not supported",
                    FixTags.GAP_FILL_FLAG, 2);
            return false;
        }
        if (newSeqNo <= seqNum) {
            doLogoutAndDisconnect("GapFill NewSeqNo " + newSeqNo
                    + " must be greater than MsgSeqNum " + seqNum);
            return true;
        }
        if (newSeqNo < store.getNextTargetSeqNum()) {
            return true;
        }
        if (newSeqNo > seqNum + 1) {
            for (long skipped = seqNum; skipped < newSeqNo - 1; skipped++) {
                if (reorderBuffer.containsKey(skipped)) {
                    reorderBuffer.remove(skipped);
                }
            }
        }
        store.setNextTargetSeqNum(newSeqNo);
        pumpReorderBuffer();
        if (state == SessionState.RESYNC) {
            reachActiveIfCaughtUp();
        }
        return true;
    }

    // ---- outbound application path -------------------------------------

    private void pumpOutbound() {
        FixMessage message = outboundQueue.poll();
        if (message != null) {
            sendWithNextSeq(message);
        }
    }

    private void drainOutboundQueue() {
        FixMessage message;
        while ((message = outboundQueue.poll()) != null) {
            sendWithNextSeq(message);
        }
    }

    private FixMessage newAdmin(String msgType) {
        FixMessage message = new FixMessage();
        message.addField(FixTags.MSG_TYPE, msgType);
        return message;
    }

    private FixMessage withEnvelope(FixMessage message) {
        message.addField(FixTags.SENDER_COMP_ID, sessionId.getSenderCompId());
        message.addField(FixTags.TARGET_COMP_ID, sessionId.getTargetCompId());
        message.addField(FixTags.SENDING_TIME, TIMESTAMP_FORMAT.format(clock.instant()));
        return message;
    }

    private void sendAdmin(String msgType, String text) {
        FixMessage message = newAdmin(msgType);
        if (text != null) {
            message.addField(FixTags.TEXT, abbreviate(text));
        }
        sendWithNextSeq(message);
    }

    private void sendWithNextSeq(FixMessage message) {
        long seqNum = store.getNextSenderSeqNum();
        withEnvelope(message).addField(FixTags.MSG_SEQ_NUM, String.valueOf(seqNum));
        String raw = message.encode(config.getBeginString());
        store.saveOutgoing(seqNum, raw);
        transportSend(raw);
        lastOutboundActivityMillis = clock.millis();
    }

    private void sendRawWithSeq(long seqNum, String raw) {
        transportSend(raw);
        lastOutboundActivityMillis = clock.millis();
    }

    private void transportSend(String raw) {
        try {
            transport.send(raw);
        } catch (Exception e) {
            disconnect("transport write failed: " + e.getMessage());
        }
    }

    // ---- heartbeat / test request --------------------------------------

    private void scheduleHeartbeatTick() {
        Object handle = taskRunner.schedule(this::onHeartbeatTick,
                config.getHeartBtInt());
        timerHandles.add(handle);
    }

    private void onHeartbeatTick() {
        if (state == SessionState.DISCONNECTED || closed.get()) {
            return;
        }
        long now = clock.millis();
        long intervalMillis = config.getHeartBtInt().toMillis();

        if (isEstablished()) {
            if (now - lastOutboundActivityMillis >= intervalMillis) {
                sendAdmin(MsgType.HEARTBEAT, null);
            }
            if (now - lastInboundActivityMillis >= intervalMillis && pendingTestReqId == null) {
                pendingTestReqId = "TR" + (++testReqCounter);
                FixMessage testRequest = newAdmin(MsgType.TEST_REQUEST);
                testRequest.addField(FixTags.TEST_REQ_ID, pendingTestReqId);
                sendWithNextSeq(testRequest);
            } else if (pendingTestReqId != null
                    && now - lastInboundActivityMillis >= 2 * intervalMillis) {
                disconnect("peer inactive for two heartbeat intervals; test request "
                        + pendingTestReqId + " unanswered");
                return;
            }
        }
        scheduleHeartbeatTick();
    }

    private boolean isEstablished() {
        return state == SessionState.ACTIVE || state == SessionState.RESYNC
                || state == SessionState.RESET_REQUEST_SENT
                || state == SessionState.RESET_REQUEST_RECEIVED
                || state == SessionState.RESET_PENDING_PEER
                || state == SessionState.LOGOUT_SENT
                || state == SessionState.LOGOUT_RECEIVED;
    }

    // ---- negotiated mid-session sequence reset -------------------------

    private void onResetTimeout() {
        resetTimer = null;
        if (state == SessionState.ACTIVE) {
            return;
        }
        if (resetAttempts >= config.getMaxResendAttempts()) {
            disconnect("sequence reset not confirmed by peer after " + resetAttempts
                    + " attempts");
            return;
        }
        resetAttempts++;
        switch (state) {
            case RESET_REQUEST_SENT -> sendPossDup(resetTriggerSeq, newAdmin(RESET_REQUEST));
            case RESET_REQUEST_RECEIVED ->
                    sendPossDup(resetTriggerSeq + 1, newAdmin(RESET_CONFIRM));
            case RESET_PENDING_PEER -> sendPossDup(1, newAdmin(MsgType.HEARTBEAT));
            default -> {
                return;
            }
        }
        rescheduleResetProbe();
    }

    private void handleResetRequest(FixMessage message) {
        if (state == SessionState.RESET_REQUEST_SENT) {
            // Both sides initiated simultaneously: acceptor yields to the initiator.
            if (role == SessionRole.ACCEPTOR) {
                becomeResetReceiver(message);
            }
            return;
        }
        if (state == SessionState.RESET_REQUEST_RECEIVED
                || state == SessionState.RESET_PENDING_PEER) {
            return;
        }
        if (state != SessionState.ACTIVE) {
            rejectOrDisconnect(message, "reset request illegal in state " + state,
                    FixTags.MSG_TYPE, 2);
            return;
        }
        if (message.getSeqNum() != store.getNextTargetSeqNum()) {
            rejectOrDisconnect(message, "reset request must carry the next expected sequence",
                    FixTags.MSG_SEQ_NUM, 5);
            return;
        }
        becomeResetReceiver(message);
    }

    private void becomeResetReceiver(FixMessage request) {
        drainOutboundQueue();
        transition(SessionState.RESET_REQUEST_RECEIVED);
        resetTriggerSeq = request.getSeqNum();
        resetAttempts = 1;
        cancelResetTimer();
        sendWithNextSeq(newAdmin(RESET_CONFIRM));
        rescheduleResetProbe();
    }

    private boolean handleResetConfirm(FixMessage message) {
        if (state != SessionState.RESET_REQUEST_SENT) {
            return false;
        }
        transition(SessionState.RESET_PENDING_PEER);
        store.resetSequenceNumbers(1, 1);
        reorderBuffer.clear();
        store.setResetPending(true);
        sendEpochHeartbeat(1);
        rescheduleResetProbe();
        return true;
    }

    private void onPeerResetSignal(String raw, FixMessage message) {
        long seqNum = message.getSeqNum();
        if (state == SessionState.RESET_REQUEST_RECEIVED) {
            store.resetSequenceNumbers(1, 1);
            reorderBuffer.clear();
            store.setResetPending(true);
            transition(SessionState.RESET_PENDING_PEER);
            sendEpochHeartbeat(1);
            sendEpochHeartbeat(2);
            peerResetConfirmed();
            return;
        }
        if (state == SessionState.RESET_PENDING_PEER) {
            if (seqNum == 1) {
                sendEpochHeartbeat(2);
            }
            peerResetConfirmed();
            return;
        }
        handleOldSequence(message);
    }

    private void sendEpochHeartbeat(long epochSeqNum) {
        FixMessage heartbeat = newAdmin(MsgType.HEARTBEAT);
        withEnvelope(heartbeat).addField(FixTags.MSG_SEQ_NUM, "1");
        if (epochSeqNum > 1) {
            heartbeat.addField(FixTags.POSS_DUP_FLAG, "Y");
        }
        String raw = heartbeat.encode(config.getBeginString());
        transportSend(raw);
        lastOutboundActivityMillis = clock.millis();
    }

    private void rescheduleResetProbe() {
        cancelResetTimer();
        if (state != SessionState.ACTIVE) {
            resetTimer = taskRunner.schedule(this::onResetTimeout, config.getResetTimeout());
        }
    }

    private void peerResetConfirmed() {
        if (state != SessionState.RESET_PENDING_PEER) {
            return;
        }
        transition(SessionState.ACTIVE);
        store.setResetPending(false);
        rescheduleResetProbe();
        pumpOutbound();
    }

    private void sendPossDup(long seqNum, FixMessage message) {
        withEnvelope(message).addField(FixTags.MSG_SEQ_NUM, String.valueOf(seqNum));
        message.addField(FixTags.POSS_DUP_FLAG, "Y");
        transportSend(message.encode(config.getBeginString()));
        lastOutboundActivityMillis = clock.millis();
    }

    private void cancelResetTimer() {
        if (resetTimer != null) {
            taskRunner.cancel(resetTimer);
            resetTimer = null;
        }
    }

    // ---- state machine / teardown --------------------------------------

    private void transition(SessionState target) {
        SessionState current = state;
        if (!allowed(current, target)) {
            throw new IllegalStateException(new FixSessionException.IllegalTransition(
                    "illegal session transition " + current + " -> " + target));
        }
        state = target;
        if (target == SessionState.ACTIVE && current != SessionState.ACTIVE) {
            store.setResetPending(false);
        }
    }

    private static boolean allowed(SessionState from, SessionState to) {
        if (to == SessionState.DISCONNECTED) {
            return true;
        }
        return switch (from) {
            case DISCONNECTED -> to == SessionState.LOGON_SENT
                    || to == SessionState.LOGON_RECEIVED;
            case LOGON_SENT -> to == SessionState.RESYNC
                    || to == SessionState.LOGOUT_SENT;
            case LOGON_RECEIVED -> to == SessionState.RESYNC
                    || to == SessionState.LOGOUT_SENT;
            case RESYNC -> to == SessionState.ACTIVE
                    || to == SessionState.LOGOUT_SENT;
            case ACTIVE -> to == SessionState.RESET_REQUEST_SENT
                    || to == SessionState.RESET_REQUEST_RECEIVED
                    || to == SessionState.LOGOUT_SENT
                    || to == SessionState.LOGOUT_RECEIVED
                    || to == SessionState.RESYNC;
            case RESET_REQUEST_SENT -> to == SessionState.RESET_PENDING_PEER
                    || to == SessionState.LOGOUT_SENT
                    || to == SessionState.ACTIVE;
            case RESET_REQUEST_RECEIVED -> to == SessionState.RESET_PENDING_PEER
                    || to == SessionState.LOGOUT_SENT
                    || to == SessionState.ACTIVE;
            case RESET_PENDING_PEER -> to == SessionState.ACTIVE
                    || to == SessionState.LOGOUT_SENT;
            case LOGOUT_SENT -> to == SessionState.DISCONNECTED
                    || to == SessionState.LOGOUT_RECEIVED;
            case LOGOUT_RECEIVED -> to == SessionState.DISCONNECTED;
        };
    }

    private void doLogoutAndDisconnect(String reason) {
        if (state == SessionState.DISCONNECTED) {
            return;
        }
        application.onError(sessionId, reason);
        if (state != SessionState.LOGOUT_SENT && state != SessionState.LOGOUT_RECEIVED) {
            transition(SessionState.LOGOUT_SENT);
            sendAdmin(MsgType.LOGOUT, reason);
        }
        taskRunner.schedule(this::teardown, Duration.ofMillis(100));
    }

    private void disconnect(String reason) {
        if (state == SessionState.DISCONNECTED) {
            return;
        }
        application.onError(sessionId, reason);
        teardown();
    }

    private void teardown() {
        if (state == SessionState.DISCONNECTED) {
            return;
        }
        if (pollHandle != null) {
            taskRunner.cancel(pollHandle);
            pollHandle = null;
        }
        for (Object handle : timerHandles) {
            taskRunner.cancel(handle);
        }
        timerHandles.clear();
        if (resendTimer != null) {
            taskRunner.cancel(resendTimer);
            resendTimer = null;
        }
        cancelResetTimer();
        state = SessionState.DISCONNECTED;
        logoutAckLatch.countDown();
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        store.close();
        application.onLogout(sessionId);
    }
}
