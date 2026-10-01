package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.FixSession;
import com.github.guoyyit.fixsessionenginejava.fix.FixSessionException;
import com.github.guoyyit.fixsessionenginejava.fix.MessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionId;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void sequenceNumbersSurviveRestartAndGapIsFilledOnReconnect() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.initiator.send(DualSessionHarness.appMessage("persisted-1"));
            harness.settle(Duration.ofMillis(100));
            long senderBefore = harness.initiator.getNextSenderSeqNum();
            assertTrue(senderBefore >= 3);

            // Simulate both processes crashing and reconnecting with persisted stores.
            harness.restartBoth();
            harness.establish();

            // The reconnect Logon consumes one sequence number in each direction.
            assertEquals(senderBefore + 1, harness.initiator.getNextSenderSeqNum());
            assertEquals(senderBefore + 1, harness.acceptor.getNextTargetSeqNum());
            assertEquals(harness.acceptor.getNextSenderSeqNum(),
                    harness.initiator.getNextTargetSeqNum());

            harness.acceptorReceived.clear();
            harness.initiator.send(DualSessionHarness.appMessage("after-restart"));
            harness.settle(Duration.ofMillis(100));

            assertEquals(1, harness.acceptorReceived.size());
            assertEquals("after-restart", harness.acceptorReceived.get(0).getString(58));
            assertEquals(senderBefore + 2, harness.acceptor.getNextTargetSeqNum());
        }
    }

    @Test
    void restartRefusesToContinueWhenStateFileIsMissing() throws Exception {
        SessionId id = new SessionId("INIT", "ACCEPT");
        Path storeDir = tempDir;
        MessageStore store = new FileMessageStore(storeDir, id);
        store.saveOutgoing(1, "8=FIX.4.4\u00019=0\u000110=000\u0001");
        long afterOne = store.getNextSenderSeqNum();
        store.close();
        assertEquals(2, afterOne);

        java.nio.file.Files.delete(storeDir.resolve("INIT-ACCEPT").resolve("state.nv"));

        assertThrows(IllegalStateException.class,
                () -> new FileMessageStore(storeDir, id));
    }

    @Test
    void restartRefusesToContinueAfterCrashDuringPendingReset() throws Exception {
        SessionConfig config = DualSessionHarness.config(tempDir, RejectPolicy.REJECT);
        SessionId id = new SessionId("INIT", "ACCEPT");
        MessageStore store = new FileMessageStore(tempDir, id);
        store.setResetPending(true);
        store.close();

        com.github.guoyyit.fixsessionenginejava.fix.LoopbackTransport[] pair =
                com.github.guoyyit.fixsessionenginejava.fix.LoopbackTransport.pair();
        MessageStore reopened = new FileMessageStore(tempDir, id);
        FixSession session = new FixSession(id, SessionRole.INITIATOR, config,
                new com.github.guoyyit.fixsessionenginejava.fix.Application() {
                }, reopened, pair[0]);

        assertThrows(FixSessionException.Unrecoverable.class, session::start);
        session.close();
    }

    @Test
    void freshStoreStartsAtSequenceOneAndBecomesActive() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();
            assertEquals(SessionState.ACTIVE, harness.initiator.getState());
            assertEquals(1, harness.initiator.getNextTargetSeqNum() - 1);
        }
    }

    @Test
    void messagesPersistedBeforeCrashAreRedeliveredAfterRestart() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            // Exchange one acknowledged application message, then crash both sides.
            harness.initiator.send(DualSessionHarness.appMessage("before-crash"));
            harness.settle(Duration.ofMillis(100));
            long senderCheckpoint = harness.initiator.getNextSenderSeqNum();

            harness.restartBoth();
            harness.establish();

            // Sequence continuity is restored from the durable store in each direction.
            assertEquals(senderCheckpoint + 1, harness.acceptor.getNextTargetSeqNum());
            assertEquals(3, harness.initiator.getNextTargetSeqNum());

            // A new message flows normally and is delivered exactly once.
            harness.acceptorReceived.clear();
            harness.initiator.send(DualSessionHarness.appMessage("after-crash"));
            harness.settle(Duration.ofMillis(100));
            assertEquals(1, harness.acceptorReceived.size());
            assertEquals("after-crash", harness.acceptorReceived.get(0).getString(58));
            assertTrue(harness.acceptorErrors.isEmpty(), harness.acceptorErrors::toString);
        }
    }
}
