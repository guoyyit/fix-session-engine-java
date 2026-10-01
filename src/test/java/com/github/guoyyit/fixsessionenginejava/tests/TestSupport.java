package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.SessionConfig;
import com.github.guoyyit.fixsessionenginejava.fix.SessionId;
import com.github.guoyyit.fixsessionenginejava.fix.SessionRole;
import com.github.guoyyit.fixsessionenginejava.session.Clock;
import com.github.guoyyit.fixsessionenginejava.session.FixApplication;
import com.github.guoyyit.fixsessionenginejava.session.FixSession;
import com.github.guoyyit.fixsessionenginejava.transport.FixTransport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TestSupport {

    private TestSupport() {
    }

    static final String BEGIN_STRING = "FIX.4.4";

    static SessionConfig config(Path storage) {
        SessionConfig config = new SessionConfig();
        config.setHeartbeatInterval(Duration.ofMillis(500));
        config.setTestRequestTimeout(Duration.ofMillis(300));
        config.setResendTimeout(Duration.ofMillis(100));
        config.setMaxResendAttempts(3);
        config.setSendQueueCapacity(64);
        config.setStoragePath(storage);
        return config;
    }

    static SessionId id(SessionRole role) {
        return role == SessionRole.INITIATOR
                ? new SessionId(BEGIN_STRING, "INIT", "ACPT")
                : new SessionId(BEGIN_STRING, "ACPT", "INIT");
    }

    static final class ScriptedClock implements Clock {
        private final AtomicInteger millis = new AtomicInteger();

        @Override
        public long nowMillis() {
            return millis.get();
        }

        void advance(Duration duration) {
            millis.addAndGet((int) duration.toMillis());
        }

        void advanceMillis(long value) {
            millis.addAndGet((int) value);
        }
    }

    static final class RecordingApplication implements FixApplication {
        final List<FixMessage> received = new CopyOnWriteArrayList<>();
        final List<FixMessage> rejects = new CopyOnWriteArrayList<>();
        final CountDownLatch logonLatch = new CountDownLatch(1);
        final CountDownLatch logoutLatch = new CountDownLatch(1);

        @Override
        public void onLogon() {
            logonLatch.countDown();
        }

        @Override
        public void onLogout() {
            logoutLatch.countDown();
        }

        @Override
        public void onMessage(FixMessage message) {
            received.add(message);
        }

        @Override
        public void onSessionReject(FixMessage reject) {
            rejects.add(reject);
        }

        boolean awaitLogon(long timeoutMillis) throws InterruptedException {
            return logonLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        boolean awaitLogout(long timeoutMillis) throws InterruptedException {
            return logoutLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    static FixSession session(SessionRole role, SessionConfig config,
                              FixTransport transport, RecordingApplication application,
                              Clock clock) {
        return new FixSession(id(role), role, config, transport, application, clock);
    }
}
