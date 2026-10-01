package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogonSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void initiatorAndAcceptorEstablishSession() throws Exception {
        LoopbackLink link = new LoopbackLink();
        TestSupport.RecordingApplication initiatorApp = new TestSupport.RecordingApplication();
        TestSupport.RecordingApplication acceptorApp = new TestSupport.RecordingApplication();

        FixSession acceptor = TestSupport.session(
                com.github.guoyyit.fixsessionenginejava.fix.SessionRole.ACCEPTOR,
                TestSupport.config(tempDir.resolve("acpt")),
                link.endpointB(), acceptorApp,
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        FixSession initiator = TestSupport.session(
                com.github.guoyyit.fixsessionenginejava.fix.SessionRole.INITIATOR,
                TestSupport.config(tempDir.resolve("init")),
                link.endpointA(), initiatorApp,
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        acceptor.start();
        initiator.start();

        assertTrue(initiatorApp.awaitLogon(2000));
        assertTrue(acceptorApp.awaitLogon(2000));

        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "buy"));

        await(() -> acceptorApp.received.size() == 1);
        org.junit.jupiter.api.Assertions.assertEquals("D",
                acceptorApp.received.get(0).msgType());

        initiator.stop();
        acceptor.stop();
    }

    private static void await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        org.junit.jupiter.api.Assertions.assertTrue(condition.getAsBoolean(),
                "condition not met before timeout");
    }

}
