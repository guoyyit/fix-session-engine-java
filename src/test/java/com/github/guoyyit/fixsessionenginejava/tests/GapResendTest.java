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

class GapResendTest {

    @TempDir
    Path tempDir;

    @Test
    void droppedMessageIsRequestedAndRedeliveredInOrder() throws Exception {
        LoopbackLink link = new LoopbackLink();
        // Frame index 1 is the initiator Logon; index 3 is the first app message
        // (logon=1, app=2 ... measured per direction from the initiator).
        // We drop frame index 3 so the second application message vanishes.
        link.setFaultPlanAtoB(new FaultPlan().dropAt(2));

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

        initiator.send(appMessage("one"));
        initiator.send(appMessage("two"));
        initiator.send(appMessage("three"));

        waitFor(() -> acceptorApp.received.size() == 3);
        assertEquals("one", acceptorApp.received.get(0).get(Tag.TEXT));
        assertEquals("two", acceptorApp.received.get(1).get(Tag.TEXT));
        assertEquals("three", acceptorApp.received.get(2).get(Tag.TEXT));

        initiator.stop();
        acceptor.stop();
    }

    private static FixMessage appMessage(String text) {
        return new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, text);
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
