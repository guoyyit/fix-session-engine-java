package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectTest {

    @TempDir
    Path tempDir;

    @Test
    void sessionsResumeSequencesAfterCleanLogout() throws Exception {
        Path initiatorStore = tempDir.resolve("init");
        Path acceptorStore = tempDir.resolve("acpt");

        LoopbackLink firstLink = new LoopbackLink();
        TestSupport.RecordingApplication firstInitiatorApp = new TestSupport.RecordingApplication();
        TestSupport.RecordingApplication firstAcceptorApp = new TestSupport.RecordingApplication();
        FixSession firstAcceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(acceptorStore), firstLink.endpointB(),
                firstAcceptorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        FixSession firstInitiator = TestSupport.session(SessionRole.INITIATOR,
                TestSupport.config(initiatorStore), firstLink.endpointA(),
                firstInitiatorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        firstAcceptor.start();
        firstInitiator.start();
        assertTrue(firstInitiatorApp.awaitLogon(2000));
        assertTrue(firstAcceptorApp.awaitLogon(2000));
        firstInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "before"));
        waitFor(() -> firstAcceptorApp.received.size() == 1);

        firstInitiator.logout();
        assertTrue(firstInitiatorApp.awaitLogout(2000));
        assertTrue(firstAcceptorApp.awaitLogout(2000));
        firstInitiator.stop();
        firstAcceptor.stop();

        LoopbackLink secondLink = new LoopbackLink();
        TestSupport.RecordingApplication secondInitiatorApp = new TestSupport.RecordingApplication();
        TestSupport.RecordingApplication secondAcceptorApp = new TestSupport.RecordingApplication();
        FixSession secondAcceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(acceptorStore), secondLink.endpointB(),
                secondAcceptorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        FixSession secondInitiator = TestSupport.session(SessionRole.INITIATOR,
                TestSupport.config(initiatorStore), secondLink.endpointA(),
                secondInitiatorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        secondAcceptor.start();
        secondInitiator.start();
        assertTrue(secondInitiatorApp.awaitLogon(2000));
        assertTrue(secondAcceptorApp.awaitLogon(2000));

        secondInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "after"));
        waitFor(() -> secondAcceptorApp.received.size() == 1);
        assertEquals("after", secondAcceptorApp.received.get(0).get(Tag.TEXT));

        secondInitiator.stop();
        secondAcceptor.stop();
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
