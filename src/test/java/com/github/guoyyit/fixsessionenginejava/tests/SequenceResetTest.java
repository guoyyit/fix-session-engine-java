package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.FixDecoder;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceResetTest {

    @TempDir
    Path tempDir;

    @Test
    void negotiatedResetContinuesFromAgreedSequence() throws Exception {
        LoopbackLink link = new LoopbackLink();
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

        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "a"));
        waitFor(() -> acceptorApp.received.size() == 1);

        initiator.resetSequenceNumbers(1);
        Thread.sleep(500);

        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "b"));
        waitFor(() -> acceptorApp.received.size() == 2);
        assertEquals("b", acceptorApp.received.get(1).get(Tag.TEXT));

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
