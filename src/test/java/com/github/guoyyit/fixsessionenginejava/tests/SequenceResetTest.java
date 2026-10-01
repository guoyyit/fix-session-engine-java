package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceResetTest {

    @TempDir
    Path tempDir;

    @Test
    void negotiatedResetReturnsBothSidesToSequenceOne() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.initiator.send(DualSessionHarness.appMessage("before-reset"));
            harness.settle(Duration.ofMillis(100));
            assertTrue(harness.initiator.getNextSenderSeqNum() >= 3);

            harness.initiator.resetSequenceNumbers(Duration.ofSeconds(5));
            harness.settle(Duration.ofMillis(200));

            assertEquals(SessionState.ACTIVE, harness.initiator.getState());
            assertEquals(SessionState.ACTIVE, harness.acceptor.getState());
            assertEquals(1, harness.initiator.getNextSenderSeqNum());
            assertEquals(1, harness.acceptor.getNextSenderSeqNum());
            assertEquals(1, harness.initiator.getNextTargetSeqNum());
            assertEquals(1, harness.acceptor.getNextTargetSeqNum());
        }
    }

    @Test
    void messagesAfterResetAreDeliveredFromSequenceOneInOrder() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.initiator.send(DualSessionHarness.appMessage("old"));
            harness.settle(Duration.ofMillis(100));
            harness.initiator.resetSequenceNumbers(Duration.ofSeconds(5));
            harness.settle(Duration.ofMillis(200));

            harness.acceptorReceived.clear();
            harness.initiator.send(DualSessionHarness.appMessage("after-1"));
            harness.initiator.send(DualSessionHarness.appMessage("after-2"));
            harness.settle(Duration.ofMillis(200));

            assertEquals(2, harness.acceptorReceived.size());
            assertEquals("after-1", harness.acceptorReceived.get(0).getString(58));
            assertEquals("after-2", harness.acceptorReceived.get(1).getString(58));
            assertEquals(3, harness.acceptor.getNextTargetSeqNum());
            assertTrue(harness.acceptorErrors.isEmpty(), harness.acceptorErrors::toString);
        }
    }

    @Test
    void acceptorInitiatedResetAlsoConverges() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.acceptor.resetSequenceNumbers(Duration.ofSeconds(5));
            harness.settle(Duration.ofMillis(300));

            assertEquals(SessionState.ACTIVE, harness.initiator.getState());
            assertEquals(SessionState.ACTIVE, harness.acceptor.getState());
            assertEquals(1, harness.initiator.getNextTargetSeqNum());
            assertEquals(1, harness.acceptor.getNextTargetSeqNum());
        }
    }
}
