package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixException;
import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.RejectReason;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IllegalTransitionTest {

    @TempDir
    Path tempDir;

    @Test
    void cannotSendApplicationMessageBeforeLogon() {
        LoopbackLink link = new LoopbackLink();
        FixSession initiator = TestSupport.session(SessionRole.INITIATOR,
                TestSupport.config(tempDir.resolve("init")), link.endpointA(),
                new TestSupport.RecordingApplication(),
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        FixException error = assertThrows(FixException.class,
                () -> initiator.send(new FixMessage().set(Tag.MSG_TYPE, "D")));
        assertEquals(RejectReason.INVALID_STATE_TRANSITION, error.reason());
    }

    @Test
    void cannotResetSequencesBeforeLogon() {
        LoopbackLink link = new LoopbackLink();
        FixSession acceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(tempDir.resolve("acpt")), link.endpointB(),
                new TestSupport.RecordingApplication(),
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        FixException error = assertThrows(FixException.class,
                () -> acceptor.resetSequenceNumbers(1));
        assertEquals(RejectReason.INVALID_STATE_TRANSITION, error.reason());
    }
}
