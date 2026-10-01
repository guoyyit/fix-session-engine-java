package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.Application;
import com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.FixSession;
import com.github.guoyyit.fixsessionenginejava.fix.LoopbackTransport;
import com.github.guoyyit.fixsessionenginejava.fix.ManualTaskRunner;
import com.github.guoyyit.fixsessionenginejava.fix.MessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionId;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.VirtualClock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Two loopback-paired sessions driven deterministically by shared virtual
 * clocks and manual task runners.
 */
final class DualSessionHarness implements AutoCloseable {

    static final String APP_MSG_TYPE = "D";

    final List<FixMessage> initiatorReceived = new ArrayList<>();
    final List<FixMessage> acceptorReceived = new ArrayList<>();
    final List<String> initiatorErrors = new ArrayList<>();
    final List<String> acceptorErrors = new ArrayList<>();

    ManualTaskRunner initiatorRunner = new ManualTaskRunner();
    ManualTaskRunner acceptorRunner = new ManualTaskRunner();
    VirtualClock initiatorClock = new VirtualClock();
    VirtualClock acceptorClock = new VirtualClock();

    LoopbackTransport[] transports = LoopbackTransport.pair();

    FixSession initiator;
    FixSession acceptor;
    MessageStore initiatorStore;
    MessageStore acceptorStore;

    private final Path storageRoot;

    DualSessionHarness(Path storageRoot, RejectPolicy rejectPolicy) {
        this.storageRoot = storageRoot;
        SessionConfig config = SessionConfig.builder()
                .heartBtInt(Duration.ofSeconds(10))
                .resendTimeout(Duration.ofSeconds(2))
                .resetTimeout(Duration.ofSeconds(5))
                .maxResendAttempts(3)
                .storagePath(storageRoot)
                .maxOutboundQueueSize(100)
                .rejectPolicy(rejectPolicy)
                .applicationMsgType(APP_MSG_TYPE)
                .build();

        SessionId initiatorId = new SessionId("INIT", "ACCEPT");
        SessionId acceptorId = new SessionId("ACCEPT", "INIT");
        initiatorStore = new FileMessageStore(storageRoot, initiatorId);
        acceptorStore = new FileMessageStore(storageRoot, acceptorId);

        initiator = new FixSession(initiatorId, SessionRole.INITIATOR, config,
                collectingApp(initiatorReceived, initiatorErrors), initiatorStore,
                transports[0], initiatorRunner, initiatorClock);
        acceptor = new FixSession(acceptorId, SessionRole.ACCEPTOR, config,
                collectingApp(acceptorReceived, acceptorErrors), acceptorStore,
                transports[1], acceptorRunner, acceptorClock);
    }

    static SessionConfig config(Path storageRoot, RejectPolicy policy) {
        return SessionConfig.builder()
                .heartBtInt(Duration.ofSeconds(10))
                .resendTimeout(Duration.ofSeconds(2))
                .resetTimeout(Duration.ofSeconds(5))
                .maxResendAttempts(3)
                .storagePath(storageRoot)
                .maxOutboundQueueSize(100)
                .rejectPolicy(policy)
                .applicationMsgType(APP_MSG_TYPE)
                .build();
    }

    private Application collectingApp(List<FixMessage> sink, List<String> errors) {
        return new Application() {
            @Override
            public void fromApp(SessionId sessionId, FixMessage message) {
                sink.add(message);
            }

            @Override
            public void onError(SessionId sessionId, String message) {
                errors.add(message);
            }
        };
    }

    /** Establish both sessions and run until ACTIVE. */
    void establish() throws Exception {
        acceptor.start();
        initiator.start();
        settle(Duration.ofMillis(50));
        if (initiator.getState() != com.github.guoyyit.fixsessionenginejava.fix.SessionState.ACTIVE
                || acceptor.getState() != com.github.guoyyit.fixsessionenginejava.fix.SessionState.ACTIVE) {
            throw new IllegalStateException("sessions did not become ACTIVE: initiator="
                    + initiator.getState() + " acceptor=" + acceptor.getState());
        }
    }

    /** Advance both clocks and run all ready/due tasks until nothing moves. */
    void settle(Duration duration) {
        long steps = Math.max(1, duration.toMillis() / 10);
        for (long i = 0; i < steps; i++) {
            initiatorClock.advance(Duration.ofMillis(10));
            acceptorClock.advance(Duration.ofMillis(10));
            initiatorRunner.tick(Duration.ofMillis(10));
            acceptorRunner.tick(Duration.ofMillis(10));
            boolean moved;
            do {
                moved = initiatorRunner.pumpReady() | acceptorRunner.pumpReady();
            } while (moved);
        }
    }

    static FixMessage appMessage(String text) {
        FixMessage message = new FixMessage();
        message.addField(com.github.guoyyit.fixsessionenginejava.fix.FixTags.MSG_TYPE, APP_MSG_TYPE);
        message.addField(58, text);
        return message;
    }

    /**
     * Close current sessions and recreate them over a fresh loopback pair
     * reusing the on-disk stores, simulating both processes restarting.
     */
    void restartBoth() throws Exception {
        initiator.close();
        acceptor.close();

        SessionConfig config = config(storageRoot, RejectPolicy.REJECT);
        transports = LoopbackTransport.pair();

        initiatorReceived.clear();
        acceptorReceived.clear();

        SessionId initiatorId = new SessionId("INIT", "ACCEPT");
        SessionId acceptorId = new SessionId("ACCEPT", "INIT");
        initiatorStore = new FileMessageStore(storageRoot, initiatorId);
        acceptorStore = new FileMessageStore(storageRoot, acceptorId);

        initiatorRunner = new ManualTaskRunner();
        acceptorRunner = new ManualTaskRunner();
        initiatorClock = new VirtualClock();
        acceptorClock = new VirtualClock();

        initiator = new FixSession(initiatorId, SessionRole.INITIATOR, config,
                collectingApp(initiatorReceived, initiatorErrors), initiatorStore,
                transports[0], initiatorRunner, initiatorClock);
        acceptor = new FixSession(acceptorId, SessionRole.ACCEPTOR, config,
                collectingApp(acceptorReceived, acceptorErrors), acceptorStore,
                transports[1], acceptorRunner, acceptorClock);
    }

    @Override
    public void close() {
        initiator.close();
        acceptor.close();
    }
}
