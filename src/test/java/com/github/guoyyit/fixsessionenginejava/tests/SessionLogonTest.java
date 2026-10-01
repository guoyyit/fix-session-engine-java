package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLogonTest {

    @TempDir
    Path tempDir;

    @Test
    void initiatorAndAcceptorEstablishActiveSession() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            assertEquals(SessionState.ACTIVE, harness.initiator.getState());
            assertEquals(SessionState.ACTIVE, harness.acceptor.getState());
            assertEquals(2, harness.initiator.getNextSenderSeqNum());
            assertEquals(2, harness.acceptor.getNextSenderSeqNum());
            assertEquals(2, harness.initiator.getNextTargetSeqNum());
            assertEquals(2, harness.acceptor.getNextTargetSeqNum());
        }
    }

    @Test
    void applicationMessageIsDeliveredExactlyOnceInOrder() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.initiator.send(DualSessionHarness.appMessage("first"));
            harness.initiator.send(DualSessionHarness.appMessage("second"));
            harness.settle(java.time.Duration.ofMillis(50));

            assertEquals(2, harness.acceptorReceived.size());
            assertEquals("first", harness.acceptorReceived.get(0).getString(58));
            assertEquals("second", harness.acceptorReceived.get(1).getString(58));
            assertTrue(harness.initiatorErrors.isEmpty(), harness.initiatorErrors::toString);
            assertTrue(harness.acceptorErrors.isEmpty(), harness.acceptorErrors::toString);
        }
    }

    @Test
    void illegalSendBeforeLogonIsRejected() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.acceptor.start();
            com.github.guoyyit.fixsessionenginejava.fix.FixSessionException error =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            com.github.guoyyit.fixsessionenginejava.fix
                                    .FixSessionException.IllegalTransition.class,
                            () -> harness.acceptor.send(DualSessionHarness.appMessage("x")));
            assertTrue(error.getMessage().contains("LOGON_RECEIVED"));
        }
    }
}
