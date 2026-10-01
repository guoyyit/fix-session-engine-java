package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixTags;
import com.github.guoyyit.fixsessionenginejava.fix.FixSessionException;
import com.github.guoyyit.fixsessionenginejava.fix.LoopbackTransport;
import com.github.guoyyit.fixsessionenginejava.fix.MsgType;
import com.github.guoyyit.fixsessionenginejava.fix.RejectPolicy;
import com.github.guoyyit.fixsessionenginejava.fix.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityTest {

    @TempDir
    Path tempDir;

    private static boolean isType(String raw, String msgType) {
        return raw.contains(FixTags.MSG_TYPE + "=" + msgType + "\u0001");
    }

    @Test
    void droppedApplicationMessageIsRecoveredByResend() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            boolean[] dropped = {false};
            harness.transports[0].setFaultInjector((message, delivery) -> {
                if (!dropped[0] && isType(message, "D")
                        && !message.contains(FixTags.POSS_DUP_FLAG + "=Y")) {
                    dropped[0] = true;
                    return;
                }
                delivery.now(message);
            });

            harness.initiator.send(DualSessionHarness.appMessage("one"));
            harness.initiator.send(DualSessionHarness.appMessage("two"));
            harness.settle(Duration.ofSeconds(3));

            assertEquals(2, harness.acceptorReceived.size());
            assertEquals("one", harness.acceptorReceived.get(0).getString(58));
            assertEquals("two", harness.acceptorReceived.get(1).getString(58));
            assertEquals(4, harness.acceptor.getNextTargetSeqNum());
            assertTrue(harness.acceptorErrors.isEmpty(), harness.acceptorErrors::toString);
        }
    }

    @Test
    void duplicatedMessageIsDeliveredOnlyOnce() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.transports[0].setFaultInjector(
                    LoopbackTransport.Faults.duplicate(m -> isType(m, "D")));

            harness.initiator.send(DualSessionHarness.appMessage("dup"));
            harness.settle(Duration.ofMillis(100));

            assertEquals(1, harness.acceptorReceived.size());
            assertEquals("dup", harness.acceptorReceived.get(0).getString(58));
        }
    }

    @Test
    void reorderedMessagesAreBufferedAndDeliveredInOrder() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.transports[0].setFaultInjector(
                    LoopbackTransport.Faults.reorderNextTwo());

            harness.initiator.send(DualSessionHarness.appMessage("first"));
            harness.initiator.send(DualSessionHarness.appMessage("second"));
            harness.settle(Duration.ofMillis(300));

            assertEquals(2, harness.acceptorReceived.size());
            assertEquals("first", harness.acceptorReceived.get(0).getString(58));
            assertEquals("second", harness.acceptorReceived.get(1).getString(58));
        }
    }

    @Test
    void outboundQueueOverflowIsRejectedDeterministically() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();
            harness.initiatorRunner.shutdown();

            for (int i = 0; i < 100; i++) {
                harness.initiator.send(DualSessionHarness.appMessage("m" + i));
            }
            assertThrows(FixSessionException.QueueOverflow.class,
                    () -> harness.initiator.send(DualSessionHarness.appMessage("overflow")));
        }
    }

    @Test
    void heartbeatAndTestRequestKeepSessionAlive() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            harness.settle(Duration.ofSeconds(25));

            assertEquals(SessionState.ACTIVE, harness.initiator.getState());
            assertEquals(SessionState.ACTIVE, harness.acceptor.getState());
        }
    }

    @Test
    void unknownApplicationMessageTypeIsRejected() throws Exception {
        try (DualSessionHarness harness = new DualSessionHarness(tempDir, RejectPolicy.REJECT)) {
            harness.establish();

            com.github.guoyyit.fixsessionenginejava.fix.FixMessage unknown =
                    new com.github.guoyyit.fixsessionenginejava.fix.FixMessage();
            unknown.addField(FixTags.MSG_TYPE, "ZZ");
            harness.initiator.send(unknown);
            harness.settle(Duration.ofMillis(100));

            assertEquals(0, harness.acceptorReceived.size());
            assertTrue(harness.initiatorErrors.stream().anyMatch(e -> e.contains("MsgType")),
                    harness.initiatorErrors::toString);
        }
    }
}
