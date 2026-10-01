package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.FaultPlan;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReorderTest {

    @TempDir
    Path tempDir;

    @Test
    void swappedFramesAreDeliveredInSequenceOrder() throws Exception {
        LoopbackLink link = new LoopbackLink();
        // Swap the first application message (initiator frame index 2) with the
        // second application message (index 3).
        link.setFaultPlanAtoB(new FaultPlan().reorderAt(2));

        TestSupport.RecordingApplication initiatorApp = new TestSupport.RecordingApplication();
        TestSupport.RecordingApplication acceptorApp = new TestSupport.RecordingApplication();
        FixSession acceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(tempDir.resolve("acpt")),
                link.endpointB(), acceptorApp,
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        FixSession initiator = TestSupport.session(SessionRole.INITIATOR,
                TestSupport.config(tempDir.resolve("init")),
                link.endpointA(), initiatorApp,
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        acceptor.start();
        initiator.start();
        assertTrue(initiatorApp.awaitLogon(2000));
        assertTrue(acceptorApp.awaitLogon(2000));

        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "first"));
        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "second"));

        waitFor(() -> acceptorApp.received.size() == 2);
        assertEquals("first", acceptorApp.received.get(0).get(Tag.TEXT));
        assertEquals("second", acceptorApp.received.get(1).get(Tag.TEXT));

        initiator.stop();
        acceptor.stop();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met before timeout");
    }
}
