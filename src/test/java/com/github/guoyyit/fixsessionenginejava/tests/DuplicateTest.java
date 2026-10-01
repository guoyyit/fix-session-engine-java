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

class DuplicateTest {

    @TempDir
    Path tempDir;

    @Test
    void duplicatedFrameIsDeliveredExactlyOnce() throws Exception {
        LoopbackLink link = new LoopbackLink();
        link.setFaultPlanAtoB(new FaultPlan().duplicateAt(2));

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

        initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "only"));

        Thread.sleep(800);
        assertEquals(1, acceptorApp.received.size());
        assertEquals("only", acceptorApp.received.get(0).get(Tag.TEXT));

        initiator.stop();
        acceptor.stop();
    }
}
