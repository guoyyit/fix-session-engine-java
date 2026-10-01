package com.github.guoyyit.fixsessionenginejava.session;

import com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.FixDecoder;
import com.github.guoyyit.fixsessionenginejava.fix.FixEncoder;
import com.github.guoyyit.fixsessionenginejava.fix.FixException;
import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.MessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.MsgType;
import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.RejectReason;
import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionId;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.SessionState;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.transport.FixTransport;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FIX 4.4 session engine: logon/logout/heartbeat state machine,
 * sequence-number reliability, resend, sequence reset and crash recovery.
 * See {@code SESSION_ENGINE.md} for the full state machine and semantics.
 */
public final class FixSession {

    private final SessionId sessionId;
    private final SessionRole role;
    private final SessionConfig config;
    private final FixTransport transport;
    private final FixApplication application;
    private final Clock clock;
    private final MessageStore store;
    private final FixEncoder encoder;
    private final FixDecoder decoder;

    private final Object stateLock = new Object();
    private final java.util.concurrent.PriorityBlockingQueue<Outbound> outboundQueue;
    private final java.util.concurrent.Semaphore outboundPermits;
    private final AtomicReference<SessionState> state =
            new AtomicReference<>(SessionState.DISCONNECTED);

    private volatile Thread senderThread;
    private volatile Thread receiverThread;
    private volatile boolean running;
    private volatile boolean stopRequested;

    private long lastOutgoingActivity;
    private long lastIncomingActivity;

    private int resendBeginSeq;
    private int resendEndSeq;
    private int resendAttempts;
    private long resendDeadline;
    private int waitingResendUpTo;
    private String pendingTestReqId;
    private long testRequestDeadline;
    private final java.util.TreeMap<Integer, FixMessage> parked = new java.util.TreeMap<>();
    private int resetAwaitingAck;

    public FixSession(SessionId sessionId, SessionRole role, SessionConfig config,
                      FixTransport transport, FixApplication application) {
        this(sessionId, role, config, transport, application, Clock.system());
    }

    public FixSession(SessionId sessionId, SessionRole role, SessionConfig config,
                      FixTransport transport, FixApplication application, Clock clock) {
        this.sessionId = sessionId;
        this.role = role;
        this.config = config;
        this.transport = transport;
        this.application = application;
        this.clock = clock;
        this.encoder = new FixEncoder(config.getBeginString());
        this.decoder = new FixDecoder(config.getBeginString());
            this.outboundQueue =
                    new java.util.concurrent.PriorityBlockingQueue<>(
                            Math.max(16, config.getSendQueueCapacity()));
            this.outboundPermits =
                    new java.util.concurrent.Semaphore(config.getSendQueueCapacity());
        this.store = config.getStoragePath() == null
                ? new InMemoryMessageStore()
                : new FileMessageStore(config.getStoragePath(), sessionId);
    }

    public void start() {
        synchronized (stateLock) {
            if (running) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "session already started");
            }
            running = true;
            lastIncomingActivity = clock.nowMillis();
            lastOutgoingActivity = clock.nowMillis();
            receiverThread = new Thread(this::receiveLoop, "fix-receiver-" + sessionId);
            senderThread = new Thread(this::sendLoop, "fix-sender-" + sessionId);
            receiverThread.setDaemon(true);
            senderThread.setDaemon(true);
            receiverThread.start();
            senderThread.start();
            if (role == SessionRole.INITIATOR) {
                enqueueOrReject(MessageBuilder.logon(
                        sessionId.senderCompId(), sessionId.targetCompId(),
                        config.getHeartbeatInterval(), config.isResetOnLogon(),
                        clock.nowMillis()));
                state.set(SessionState.LOGON_SENT);
            }
        }
    }

    public SessionState state() {
        return state.get();
    }

    public void send(FixMessage message) {
        if (state.get() != SessionState.ACTIVE) {
            throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                    "cannot send application message in state " + state.get());
        }
        if (isAdministrative(message.msgType())) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "administrative messages are managed by the engine");
        }
        if (!message.has(Tag.SENDER_COMP_ID)) {
            message.set(Tag.SENDER_COMP_ID, sessionId.senderCompId());
        }
        if (!message.has(Tag.TARGET_COMP_ID)) {
            message.set(Tag.TARGET_COMP_ID, sessionId.targetCompId());
        }
        enqueueOrReject(message);
    }

    public void logout() {
        if (!isAlive()) {
            return;
        }
        enqueueOrReject(MessageBuilder.logout(
                sessionId.senderCompId(), sessionId.targetCompId(), clock.nowMillis()));
        state.compareAndSet(SessionState.ACTIVE, SessionState.LOGOUT_SENT);
        state.compareAndSet(SessionState.LOGON_SENT, SessionState.LOGOUT_SENT);
    }

    public void resetSequenceNumbers(int newSeqNo) {
        if (newSeqNo < 1) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "newSeqNo must be >= 1");
        }
        synchronized (stateLock) {
            if (state.get() != SessionState.ACTIVE) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "sequence reset allowed only in ACTIVE state");
            }
            if (resetAwaitingAck != 0) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "sequence reset already in progress");
            }
            enqueueOrReject(MessageBuilder.sequenceReset(
                    sessionId.senderCompId(), sessionId.targetCompId(), newSeqNo,
                    false, false, clock.nowMillis()));
            resetAwaitingAck = newSeqNo;
        }
    }

    public void stop() {
        stopRequested = true;
        running = false;
        closeQuietly();
        joinThreads();
        store.close();
        state.set(SessionState.CLOSED);
    }

    private void sendLoop() {
        try {
            while (running) {
                Outbound outbound = outboundQueue.poll(50, TimeUnit.MILLISECONDS);
                long now = clock.nowMillis();
                if (outbound != null) {
                    outboundPermits.release();
                    transmit(outbound);
                    lastOutgoingActivity = now;
                } else {
                    tick(now);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failSession(e);
        }
    }

    private void tick(long now) {
        synchronized (stateLock) {
            SessionState current = state.get();
            if (current != SessionState.ACTIVE && current != SessionState.LOGON_SENT) {
                return;
            }
            long heartbeatMillis = config.getHeartbeatInterval().toMillis();
            if (now - lastOutgoingActivity >= heartbeatMillis) {
                enqueueOrReject(MessageBuilder.heartbeat(
                        sessionId.senderCompId(), sessionId.targetCompId(), null,
                        false, now));
                lastOutgoingActivity = now;
            }
            if (pendingTestReqId == null
                    && now - lastIncomingActivity >= heartbeatMillis) {
                pendingTestReqId = "TR-" + Long.toHexString(now);
                enqueueOrReject(MessageBuilder.testRequest(
                        sessionId.senderCompId(), sessionId.targetCompId(),
                        pendingTestReqId, now));
                testRequestDeadline = now + config.getTestRequestTimeout().toMillis();
            } else if (pendingTestReqId != null && now >= testRequestDeadline) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "peer did not answer TestRequest " + pendingTestReqId
                                + " within " + config.getTestRequestTimeout());
            }
            if (waitingResendUpTo != 0 && now >= resendDeadline) {
                if (resendAttempts >= config.getMaxResendAttempts()) {
                    throw new FixException(RejectReason.GAP_TOO_LARGE,
                            "gap " + resendBeginSeq + ".." + resendEndSeq
                                    + " not filled after " + resendAttempts
                                    + " resend requests");
                }
                resendAttempts++;
                resendDeadline = now + config.getResendTimeout().toMillis();
                enqueueOrReject(MessageBuilder.resendRequest(
                        sessionId.senderCompId(), sessionId.targetCompId(),
                        resendBeginSeq, resendEndSeq, now));
            }
        }
    }

    private void receiveLoop() {
        try {
            while (running || state.get() == SessionState.LOGOUT_SENT) {
                byte[] frame = transport.receive();
                FixMessage message;
                try {
                    message = decoder.decode(frame);
                } catch (FixException e) {
                    onCodecFailure(e, frame);
                    continue;
                }
                try {
                    handleInbound(message);
                } catch (FixException e) {
                    onMessageRejected(message, e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!stopRequested) {
                failSession(e);
            }
        }
    }

    private void handleInbound(FixMessage message) {
        verifyHeader(message);
        int incomingSeq = message.getInt(Tag.MSG_SEQ_NUM);

        synchronized (stateLock) {
            lastIncomingActivity = clock.nowMillis();
            SessionState current = state.get();
            String type = message.msgType();

            if (MsgType.LOGON.equals(type)) {
                handleLogon(message, incomingSeq);
                return;
            }
            if (MsgType.LOGOUT.equals(type)) {
                handleLogout(message, incomingSeq);
                return;
            }
            boolean preLogonRecovery = role == SessionRole.ACCEPTOR
                    && current == SessionState.DISCONNECTED
                    && (MsgType.SEQUENCE_RESET.equals(type)
                    || "Y".equals(message.get(Tag.POSS_DUP_FLAG)));
            boolean recoveryDuringLogon = current == SessionState.LOGON_SENT
                    && (MsgType.RESEND_REQUEST.equals(type)
                    || MsgType.SEQUENCE_RESET.equals(type));
            if (current != SessionState.ACTIVE
                    && !recoveryDuringLogon && !preLogonRecovery) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "msgType " + type + " rejected in state " + current);
            }

            if (incomingSeq < store.nextTargetSeq()) {
                handlePossibleDuplicate(message, incomingSeq);
                return;
            }
            if (incomingSeq > store.nextTargetSeq()) {
                handleGap(message, incomingSeq);
                return;
            }
            processInOrder(message, incomingSeq);
        }
    }

    private void onCodecFailure(FixException error, byte[] frame) {
        if (config.getRejectPolicy() == RejectPolicy.SILENT_DROP) {
            return;
        }
        int refSeq = -1;
        String raw = new String(frame, StandardCharsets.US_ASCII);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\u000134=(\\d+)").matcher(raw);
        if (matcher.find()) {
            try {
                refSeq = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // leave as -1
            }
        }
        sendReject(refSeq, null, error.reason(), error.refTag(), error.getMessage());
        if (config.getRejectPolicy() == RejectPolicy.REJECT_AND_DISCONNECT) {
            logout();
        }
    }

    private void onMessageRejected(FixMessage message, FixException error) {
        if (config.getRejectPolicy() == RejectPolicy.SILENT_DROP) {
            return;
        }
        sendReject(message.getInt(Tag.MSG_SEQ_NUM), message.msgType(),
                error.reason(), error.refTag(), error.getMessage());
        if (config.getRejectPolicy() == RejectPolicy.REJECT_AND_DISCONNECT) {
            logout();
        } else if (error.reason() == RejectReason.INVALID_STATE_TRANSITION
                && !isAlive()) {
            running = false;
        }
    }

    private void transmit(FixMessage message, int resendSeq) {
        transmit(new Outbound(message, resendSeq, resendSeq > 0));
    }

    private void transmit(Outbound outbound) {
        FixMessage message = outbound.message();
        int seq;
        byte[] frame;
        if (outbound.assignedSeq() > 0 && outbound.replayStored()) {
            seq = outbound.assignedSeq();
            frame = buildResendFrame(seq);
        } else if (outbound.assignedSeq() > 0) {
            seq = outbound.assignedSeq();
            message.setInt(Tag.MSG_SEQ_NUM, seq);
            frame = encoder.encode(message);
        } else {
            seq = store.nextSenderSeq();
            message.setInt(Tag.MSG_SEQ_NUM, seq);
            frame = encoder.encode(message);
            store.saveOutgoing(seq, frame);
        }
        try {
            transport.send(frame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                    "interrupted while sending");
        }
    }

    private byte[] buildResendFrame(int seq) {
        FixMessage decoded = decoder.decode(store.loadOutgoing(seq));
        decoded.set(Tag.POSS_DUP_FLAG, "Y");
        String type = decoded.msgType();
        if (!MsgType.SEQUENCE_RESET.equals(type) && !MsgType.HEARTBEAT.equals(type)) {
            decoded.set(Tag.POSS_RESEND_FLAG, "Y");
        }
        return encoder.encode(decoded);
    }

    private void verifyHeader(FixMessage message) {
        if (!sessionId.targetCompId().equals(message.get(Tag.SENDER_COMP_ID))) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "unexpected SenderCompID " + message.get(Tag.SENDER_COMP_ID),
                    Tag.SENDER_COMP_ID);
        }
        if (!sessionId.senderCompId().equals(message.get(Tag.TARGET_COMP_ID))) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "unexpected TargetCompID " + message.get(Tag.TARGET_COMP_ID),
                    Tag.TARGET_COMP_ID);
        }
        int seq = message.getInt(Tag.MSG_SEQ_NUM);
        if (seq < 1) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "MsgSeqNum must be >= 1", Tag.MSG_SEQ_NUM);
        }
    }

    private void handleLogon(FixMessage message, int incomingSeq) {
        SessionState current = state.get();
        if (role == SessionRole.INITIATOR && current != SessionState.LOGON_SENT) {
            throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                    "unexpected Logon in state " + current);
        }
        if (role == SessionRole.ACCEPTOR && current != SessionState.DISCONNECTED) {
            throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                    "duplicate Logon in state " + current);
        }
        if (!message.has(Tag.HEART_BT_INT)) {
            throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                    "Logon missing HeartBtInt(108)", Tag.HEART_BT_INT);
        }
        boolean peerReset = "Y".equals(message.get(Tag.RESET_SEQ_NUM_FLAG));
        boolean resetRequested = peerReset
                && (role == SessionRole.INITIATOR ? config.isResetOnLogon() : true);
        if (resetRequested) {
            if (incomingSeq != 1) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "Logon with ResetSeqNumFlag must carry MsgSeqNum=1", Tag.MSG_SEQ_NUM);
            }
            store.resetSequences(1, 1);
        } else if (incomingSeq > store.nextTargetSeq()) {
            sendResendRequest(store.nextTargetSeq(), 0);
        } else if (incomingSeq < store.nextTargetSeq()) {
            sendLogout("MsgSeqNum too low, expecting " + store.nextTargetSeq());
            throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                    "Logon seq " + incomingSeq + " below expected " + store.nextTargetSeq());
        }
        if (incomingSeq == store.nextTargetSeq() || resetRequested) {
            store.setNextTargetSeq(incomingSeq + 1);
        }
        if (role == SessionRole.ACCEPTOR) {
            enqueueOrReject(MessageBuilder.logon(
                    sessionId.senderCompId(), sessionId.targetCompId(),
                    config.getHeartbeatInterval(),
                    peerReset && config.isResetOnLogon(), clock.nowMillis()));
        }
        state.set(SessionState.ACTIVE);
        lastIncomingActivity = clock.nowMillis();
        application.onLogon();
    }

    private void handleLogout(FixMessage message, int incomingSeq) {
        if (!isAlive()) {
            return;
        }
        if (incomingSeq == store.nextTargetSeq()) {
            store.setNextTargetSeq(incomingSeq + 1);
        }
        if (state.get() == SessionState.LOGOUT_SENT) {
            finishLogout();
        } else {
            state.set(SessionState.LOGOUT_SENT);
            enqueueOrReject(MessageBuilder.logout(
                    sessionId.senderCompId(), sessionId.targetCompId(), clock.nowMillis()));
            finishLogout();
        }
    }

    private void finishLogout() {
        state.set(SessionState.CLOSED);
        application.onLogout();
    }

    private void handlePossibleDuplicate(FixMessage message, int incomingSeq) {
        // Application duplicates are never re-delivered. Administrative
        // duplicates are dispatched idempotently (e.g. TestRequest re-answer).
        String type = message.msgType();
        if (!isAdministrative(type)) {
            return;
        }
        dispatchAdministrative(message, incomingSeq);
    }

    private void handleGap(FixMessage message, int incomingSeq) {
        if (resetAwaitingAck != 0) {
            parkOutOfOrder(message, incomingSeq);
            return;
        }
        if (waitingResendUpTo == 0) {
            waitingResendUpTo = incomingSeq;
            requestResend(store.nextTargetSeq(), incomingSeq - 1);
        } else if (incomingSeq > waitingResendUpTo) {
            waitingResendUpTo = incomingSeq;
        }
        parkOutOfOrder(message, incomingSeq);
    }

    private void processInOrder(FixMessage message, int incomingSeq) {
        String type = message.msgType();
        if (isAdministrative(type)) {
            if (MsgType.SEQUENCE_RESET.equals(type)) {
                handleSequenceReset(message, incomingSeq);
                return;
            }
            dispatchAdministrative(message, incomingSeq);
        } else {
            boolean preLogon = state.get() == SessionState.DISCONNECTED;
            boolean unknown = !isKnownApplication(type);
            if (unknown) {
                refuseInbound(message, RejectReason.UNKNOWN_MESSAGE_TYPE, 0,
                        "unsupported msgType " + type);
            }
            if (!preLogon && !unknown) {
                application.onMessage(message);
            }
        }
        store.setNextTargetSeq(incomingSeq + 1);
        store.markDelivered(incomingSeq);
        drainParked();
    }

    private void refuseInbound(FixMessage message, RejectReason reason, int refTag,
                               String text) {
        if (config.getRejectPolicy() == RejectPolicy.SILENT_DROP) {
            return;
        }
        sendReject(message.getInt(Tag.MSG_SEQ_NUM), message.msgType(), reason,
                refTag, text);
        if (config.getRejectPolicy() == RejectPolicy.REJECT_AND_DISCONNECT) {
            logout();
        }
    }

    private boolean isKnownApplication(String type) {
        java.util.Set<String> allowed = config.getAllowedAppMsgTypes();
        return allowed.isEmpty() || allowed.contains(type);
    }

    private void dispatchAdministrative(FixMessage message, int incomingSeq) {
        switch (message.msgType()) {
            case MsgType.HEARTBEAT -> handleHeartbeat(message);
            case MsgType.TEST_REQUEST -> handleTestRequest(message);
            case MsgType.RESEND_REQUEST -> handleResendRequest(message);
            case MsgType.SEQUENCE_RESET -> handleSequenceReset(message, incomingSeq);
            case MsgType.REJECT -> {
                application.onSessionReject(message);
            }
            default -> throw new FixException(RejectReason.UNKNOWN_MESSAGE_TYPE,
                    "unsupported msgType " + message.msgType());
        }
    }

    private void sendReject(int refSeq, String refMsgType, RejectReason reason,
                            int refTag, String text) {
        FixMessage reject = MessageBuilder.reject(
                sessionId.senderCompId(), sessionId.targetCompId(),
                refSeq < 0 ? 0 : refSeq, refMsgType, reason, refTag, text,
                clock.nowMillis());
        enqueueOrReject(reject);
    }

    private void sendResendRequest(int beginSeqNo, int endSeqNo) {
        enqueueOrReject(MessageBuilder.resendRequest(
                sessionId.senderCompId(), sessionId.targetCompId(),
                beginSeqNo, endSeqNo, clock.nowMillis()));
    }

    private void sendLogout(String text) {
        FixMessage logout = MessageBuilder.logout(
                sessionId.senderCompId(), sessionId.targetCompId(), clock.nowMillis());
        logout.set(Tag.TEXT, text);
        enqueueOrReject(logout);
    }

    private void parkOutOfOrder(FixMessage message, int incomingSeq) {
        parked.putIfAbsent(incomingSeq, message);
    }

    private void drainParked() {
        while (true) {
            int expected = store.nextTargetSeq();
            FixMessage next = parked.remove(expected);
            if (next == null) {
                if (waitingResendUpTo != 0 && expected >= waitingResendUpTo) {
                    waitingResendUpTo = 0;
                    resendBeginSeq = 0;
                    resendEndSeq = 0;
                }
                return;
            }
            processInOrder(next, expected);
        }
    }

    private void requestResend(int beginSeqNo, int endSeqNo) {
        enqueueOrReject(MessageBuilder.resendRequest(
                sessionId.senderCompId(), sessionId.targetCompId(),
                beginSeqNo, endSeqNo, clock.nowMillis()));
        resendBeginSeq = beginSeqNo;
        resendEndSeq = endSeqNo;
        resendAttempts = 1;
        resendDeadline = clock.after(config.getResendTimeout());
    }

    private void handleHeartbeat(FixMessage message) {
        String testReqId = message.get(Tag.TEST_REQ_ID);
        if (testReqId != null && testReqId.equals(pendingTestReqId)) {
            pendingTestReqId = null;
        }
    }

    private void handleTestRequest(FixMessage message) {
        String testReqId = message.get(Tag.TEST_REQ_ID);
        if (testReqId == null) {
            throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                    "TestRequest missing TestReqID(112)", Tag.TEST_REQ_ID);
        }
        enqueueOrReject(MessageBuilder.heartbeat(
                sessionId.senderCompId(), sessionId.targetCompId(), testReqId,
                false, clock.nowMillis()));
    }

    private void handleResendRequest(FixMessage message) {
        int begin = message.getInt(Tag.BEGIN_SEQ_NO);
        int endRaw = message.getInt(Tag.END_SEQ_NO);
        int end = endRaw == 0 ? store.lastOutgoingSeq()
                : Math.min(endRaw, store.lastOutgoingSeq());
        if (begin < 1 || begin > store.lastOutgoingSeq()) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "invalid BeginSeqNo " + begin, Tag.BEGIN_SEQ_NO);
        }
        int spanStart = begin;
        for (int seq = begin; seq <= end; seq++) {
            FixMessage stored = decoder.decode(store.loadOutgoing(seq));
            if (isAdministrative(stored.msgType())) {
                continue;
            }
            if (spanStart < seq) {
                enqueue(new Outbound(MessageBuilder.sequenceReset(
                        sessionId.senderCompId(), sessionId.targetCompId(), seq,
                        true, true, clock.nowMillis()), spanStart, false));
            }
            enqueue(new Outbound(stored, seq, true));
            spanStart = seq + 1;
        }
        if (spanStart <= end) {
            enqueue(new Outbound(MessageBuilder.sequenceReset(
                    sessionId.senderCompId(), sessionId.targetCompId(), end + 1,
                    true, true, clock.nowMillis()), spanStart, false));
        }
    }

    private void handleSequenceReset(FixMessage message, int incomingSeq) {
        boolean gapFill = "Y".equals(message.get(Tag.GAP_FILL_FLAG));
        int newSeqNo = message.getInt(Tag.NEW_SEQ_NO);
        if (gapFill) {
            if (incomingSeq > store.nextTargetSeq()) {
                throw new FixException(RejectReason.INVALID_STATE_TRANSITION,
                        "GapFill at seq " + incomingSeq + " while expecting "
                                + store.nextTargetSeq());
            }
            if (newSeqNo < store.nextTargetSeq()) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "GapFill NewSeqNo " + newSeqNo + " below expected "
                                + store.nextTargetSeq(), Tag.NEW_SEQ_NO);
            }
            store.setNextTargetSeq(newSeqNo);
            drainParked();
        } else {
            handleExplicitReset(message, incomingSeq, newSeqNo);
        }
    }

    private void handleExplicitReset(FixMessage message, int incomingSeq, int newSeqNo) {
        if (newSeqNo < 1) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "NewSeqNo must be >= 1", Tag.NEW_SEQ_NO);
        }
        if (resetAwaitingAck != 0) {
            if (newSeqNo != resetAwaitingAck) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "peer reset target " + newSeqNo + " != our proposal "
                                + resetAwaitingAck,
                        Tag.NEW_SEQ_NO);
            }
            store.setNextTargetSeq(incomingSeq + 1);
            commitReset(newSeqNo);
        } else {
            prepareReset(newSeqNo);
            store.setNextTargetSeq(incomingSeq + 1);
            enqueueOrReject(MessageBuilder.sequenceReset(
                    sessionId.senderCompId(), sessionId.targetCompId(), newSeqNo,
                    false, false, clock.nowMillis()));
        }
    }

    private void prepareReset(int newSeqNo) {
        store.resetSequences(newSeqNo, newSeqNo);
        parked.clear();
        waitingResendUpTo = 0;
        resendBeginSeq = 0;
        resendEndSeq = 0;
    }

    private void commitReset(int newSeqNo) {
        resetAwaitingAck = 0;
        drainParked();
    }

    private void enqueueOrReject(FixMessage message) {
        enqueue(Outbound.of(message, isAdministrative(message.msgType())));
    }

    private void enqueue(Outbound outbound) {
        if (!outboundPermits.tryAcquire()) {
            throw new OutboundRejectedException(
                    "outbound queue capacity " + config.getSendQueueCapacity()
                            + " exhausted (slow consumer)");
        }
        outboundQueue.offer(outbound);
    }

    private record Outbound(FixMessage message, int assignedSeq, boolean replayStored,
                            boolean priority, long enqueueSeq)
            implements Comparable<Outbound> {
        private static final java.util.concurrent.atomic.AtomicLong SEQ =
                new java.util.concurrent.atomic.AtomicLong();

        private static Outbound of(FixMessage message, boolean priority) {
            return new Outbound(message, 0, false, priority, SEQ.incrementAndGet());
        }

        private Outbound(FixMessage message) {
            this(message, 0, false, false, SEQ.incrementAndGet());
        }

        private Outbound(FixMessage message, int assignedSeq, boolean replayStored) {
            this(message, assignedSeq, replayStored, true, SEQ.incrementAndGet());
        }

        @Override
        public int compareTo(Outbound other) {
            int byPriority = Boolean.compare(other.priority, priority);
            if (byPriority != 0) {
                return byPriority;
            }
            return Long.compare(enqueueSeq, other.enqueueSeq);
        }
    }

    private boolean isAdministrative(String msgType) {
        return MsgType.HEARTBEAT.equals(msgType) || MsgType.TEST_REQUEST.equals(msgType)
                || MsgType.RESEND_REQUEST.equals(msgType) || MsgType.REJECT.equals(msgType)
                || MsgType.SEQUENCE_RESET.equals(msgType) || MsgType.LOGOUT.equals(msgType)
                || MsgType.LOGON.equals(msgType);
    }

    private boolean isAlive() {
        SessionState current = state.get();
        return current == SessionState.LOGON_SENT || current == SessionState.ACTIVE
                || current == SessionState.LOGOUT_SENT;
    }

    private void failSession(Exception error) {
        running = false;
        closeQuietly();
        state.set(SessionState.DISCONNECTED);
    }

    private void closeQuietly() {
        try {
            transport.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void joinThreads() {
        join(senderThread);
        join(receiverThread);
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
