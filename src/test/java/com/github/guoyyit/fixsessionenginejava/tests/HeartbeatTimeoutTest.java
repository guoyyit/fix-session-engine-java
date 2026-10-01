package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.FixTransport;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatTimeoutTest {

    @TempDir
    Path tempDir;

    @Test
    void silentPeerIsDetectedAndDisconnected() throws Exception {
        LoopbackLink link = new LoopbackLink();

        // Acceptor completes the logon then swallows every TestRequest.
        FixTransport blackholeAfterLogon = new FixTransport() {
            @Override
            public void send(byte[] frame) throws InterruptedException {
                String raw = new String(frame, java.nio.charset.StandardCharsets.US_ASCII);
                if (raw.contains("\u000135=A\u0001")) {
                    link.endpointB().send(frame);
                }
            }

            @Override
            public byte[] receive() throws InterruptedException {
                return link.endpointB().receive();
            }

            @Override
            public boolean isOpen() {
                return link.endpointB().isOpen();
            }

            @Override
            public void close() {
                link.endpointB().close();
            }
        };

        SessionConfig acceptorConfig = TestSupport.config(tempDir.resolve("acpt"));
        FixSession acceptor = TestSupport.session(SessionRole.ACCEPTOR, acceptorConfig,
                blackholeAfterLogon, new TestSupport.RecordingApplication(),
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        TestSupport.RecordingApplication initiatorApp = new TestSupport.RecordingApplication();
        FixSession initiator = TestSupport.session(SessionRole.INITIATOR,
                TestSupport.config(tempDir.resolve("init")), link.endpointA(),
                initiatorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        acceptor.start();
        initiator.start();
        assertTrue(initiatorApp.awaitLogon(2000));

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && initiator.state()
                == com.github.guoyyit.fixsessionenginejava.fix.SessionState.ACTIVE) {
            Thread.sleep(20);
        }
        assertTrue(initiator.state()
                        != com.github.guoyyit.fixsessionenginejava.fix.SessionState.ACTIVE,
                "initiator must disconnect after unanswered TestRequest");

        initiator.stop();
        acceptor.stop();
    }
}
