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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void restartResumesFromLastConsistentPositionAndFillsGap() throws Exception {
        Path initiatorStore = tempDir.resolve("init");
        Path acceptorStore = tempDir.resolve("acpt");

        LoopbackLink firstLink = new LoopbackLink();
        // Drop the first application message (initiator frame index 2).
        firstLink.setFaultPlanAtoB(new com.github.guoyyit.fixsessionenginejava.transport.FaultPlan()
                .dropAt(2));

        TestSupport.RecordingApplication firstInitiatorApp = new TestSupport.RecordingApplication();
        TestSupport.RecordingApplication firstAcceptorApp = new TestSupport.RecordingApplication();
        com.github.guoyyit.fixsessionenginejava.fix.SessionConfig initiatorConfig =
                TestSupport.config(initiatorStore);
        FixSession firstAcceptor = TestSupport.session(SessionRole.ACCEPTOR,
                TestSupport.config(acceptorStore), firstLink.endpointB(),
                firstAcceptorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());
        com.github.guoyyit.fixsessionenginejava.transport.FixTransport blockingReplays =
                new com.github.guoyyit.fixsessionenginejava.transport.FixTransport() {
                    @Override
                    public void send(byte[] frame) throws InterruptedException {
                        String raw = new String(frame, java.nio.charset.StandardCharsets.US_ASCII);
                        if (raw.contains("\u000143=Y\u0001")) {
                            return;
                        }
                        firstLink.endpointA().send(frame);
                    }

                    @Override
                    public byte[] receive() throws InterruptedException {
                        return firstLink.endpointA().receive();
                    }

                    @Override
                    public boolean isOpen() {
                        return firstLink.endpointA().isOpen();
                    }

                    @Override
                    public void close() {
                        firstLink.endpointA().close();
                    }
                };
        FixSession firstInitiator = TestSupport.session(SessionRole.INITIATOR,
                initiatorConfig, blockingReplays,
                firstInitiatorApp, com.github.guoyyit.fixsessionenginejava.session.Clock.system());

        firstAcceptor.start();
        firstInitiator.start();
        assertTrue(firstInitiatorApp.awaitLogon(2000));
        assertTrue(firstAcceptorApp.awaitLogon(2000));

        firstInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "m1"));
        firstInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "m2"));
        firstInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "m3"));
        // m2/m3 trigger a ResendRequest, but the process is killed before any reply.
        Thread.sleep(200);
        firstLink.endpointA().close();
        firstLink.endpointB().close();

        // Simulate a hard crash: kill both endpoints immediately without logout.
        firstInitiator.stop();
        firstAcceptor.stop();
        Thread.sleep(300);

        // Restart both processes from the same storage directories.
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

        waitFor(() -> secondAcceptorApp.received.size() == 3);
        assertEquals("m1", secondAcceptorApp.received.get(0).get(Tag.TEXT));
        assertEquals("m2", secondAcceptorApp.received.get(1).get(Tag.TEXT));
        assertEquals("m3", secondAcceptorApp.received.get(2).get(Tag.TEXT));

        secondInitiator.send(new FixMessage().set(Tag.MSG_TYPE, "D").set(Tag.TEXT, "m4"));
        waitFor(() -> secondAcceptorApp.received.size() == 4);
        assertEquals("m4", secondAcceptorApp.received.get(3).get(Tag.TEXT));

        secondInitiator.stop();
        secondAcceptor.stop();
    }

    @Test
    void corruptedStateIsRefusedInsteadOfGuessed() throws Exception {
        Path storeDir = tempDir.resolve("bad");
        com.github.guoyyit.fixsessionenginejava.fix.SessionId id =
                new com.github.guoyyit.fixsessionenginejava.fix.SessionId(
                        "FIX.4.4", "INIT", "ACPT");
        new com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore(storeDir, id).close();
        Path nested = Files.walk(storeDir).filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("state.dat"))
                .findFirst().orElseThrow();
        Files.writeString(nested, "this is not valid state\n");

        FixException error = assertThrows(FixException.class,
                () -> new com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore(
                        storeDir, id));
        assertEquals(RejectReason.UNRECOVERABLE_STORE, error.reason());
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
