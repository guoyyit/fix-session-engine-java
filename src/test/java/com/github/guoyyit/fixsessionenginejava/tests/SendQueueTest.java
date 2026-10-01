package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.session.OutboundRejectedException;
import com.github.guoyyit.fixsessionenginejava.transport.FixTransport;
import com.github.guoyyit.fixsessionenginejava.transport.LoopbackLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendQueueTest {

    @TempDir
    Path tempDir;

    @Test
    void fullQueueRejectsDeterministicallyAndDoesNotBlockSender() throws Exception {
        LoopbackLink link = new LoopbackLink();
        TestSupport.RecordingApplication acceptorApp = new TestSupport.RecordingApplication();
        FixSession acceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(tempDir.resolve("acpt")), link.endpointB(),
                acceptorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        FixTransport stalled = new FixTransport() {
            @Override
            public void send(byte[] frame) throws InterruptedException {
                String raw = new String(frame, java.nio.charset.StandardCharsets.US_ASCII);
                if (!raw.contains("\u000135=A\u0001")) {
                    Thread.sleep(60_000);
                    return;
                }
                link.endpointA().send(frame);
            }

            @Override
            public byte[] receive() throws InterruptedException {
                return link.endpointA().receive();
            }

            @Override
            public boolean isOpen() {
                return link.endpointA().isOpen();
            }

            @Override
            public void close() {
                link.endpointA().close();
            }
        };

        SessionConfig config = TestSupport.config(tempDir.resolve("init"));
        config.setSendQueueCapacity(4);
        TestSupport.RecordingApplication app = new TestSupport.RecordingApplication();
        FixSession session = TestSupport.session(SessionRole.INITIATOR, config, stalled, app,
                com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        acceptor.start();
        session.start();

        assertTrue(app.awaitLogon(2000));

        boolean rejected = false;
        for (int i = 0; i < 20; i++) {
            try {
                session.send(new FixMessage().set(Tag.MSG_TYPE, "D")
                        .set(Tag.TEXT, "msg-" + i));
            } catch (OutboundRejectedException e) {
                rejected = true;
                break;
            }
        }
        assertTrue(rejected, "expected OutboundRejectedException once the queue fills");

        session.stop();
        acceptor.stop();
    }
}
