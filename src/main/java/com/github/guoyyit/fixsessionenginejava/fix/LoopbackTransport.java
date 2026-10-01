package com.github.guoyyit.fixsessionenginejava.fix;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Local loopback {@link Transport}: two paired endpoints exchange messages
 * inside one JVM. An optional shared fault injector can drop, duplicate and
 * reorder messages to exercise the reliability machinery.
 *
 * <p>Messages are complete raw FIX strings; framing is the SOH terminator
 * already produced by {@link FixMessage#encode(String)}.
 */
public final class LoopbackTransport implements Transport {

    private static final String POISON = "__LOOPBACK_EOF__";

    /** Creates a connected pair; index 0 and 1 deliver to each other. */
    public static LoopbackTransport[] pair() {
        LinkedBlockingQueue<String> aInbox = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> bInbox = new LinkedBlockingQueue<>();
        FaultState state = new FaultState();
        LoopbackTransport a = new LoopbackTransport(aInbox, bInbox, state);
        LoopbackTransport b = new LoopbackTransport(bInbox, aInbox, state);
        return new LoopbackTransport[] {a, b};
    }

    /** Inject behaviour on messages flowing through the pair. */
    public interface FaultInjector {
        /**
         * Called once per outbound message on either side. Use the
         * {@link Delivery} handle to deliver copies immediately, defer copies
         * (released later to create reordering), or do nothing (drop).
         */
        void handle(String message, Delivery delivery);
    }

    /** Handle given to a {@link FaultInjector} to decide message fate. */
    public interface Delivery {
        /** Deliver one copy right away, ahead of any previously deferred copies. */
        void now(String message);

        /** Hold a copy for later release. */
        void defer(String message);

        /** Messages currently held by the transport pair. */
        List<String> deferred();

        /** Deliver all held messages in their deferred order. */
        void releaseAll();
    }

    /** Built-in scripted fault actions. */
    public static final class Faults {

        private Faults() {
        }

        /** Drops every message matching the predicate, passes others through. */
        public static FaultInjector drop(java.util.function.Predicate<String> predicate) {
            return (message, delivery) -> {
                if (!predicate.test(message)) {
                    delivery.now(message);
                }
            };
        }

        /** Delivers two copies of every matching message. */
        public static FaultInjector duplicate(java.util.function.Predicate<String> predicate) {
            return (message, delivery) -> {
                delivery.now(message);
                if (predicate.test(message)) {
                    delivery.now(message);
                }
            };
        }

        /** Swaps the delivery order of the next two messages passing through. */
        public static FaultInjector reorderNextTwo() {
            return new FaultInjector() {
                private boolean firstDeferred;

                @Override
                public void handle(String message, Delivery delivery) {
                    if (!firstDeferred) {
                        firstDeferred = true;
                        delivery.defer(message);
                    } else {
                        delivery.now(message);
                        delivery.releaseAll();
                        firstDeferred = false;
                    }
                }
            };
        }

        /** Passes everything through unchanged. */
        public static FaultInjector none() {
            return (message, delivery) -> delivery.now(message);
        }
    }

    private static final class FaultState {
        private volatile FaultInjector injector = Faults.none();
        private final List<String> deferred = new ArrayList<>();
    }

    private final LinkedBlockingQueue<String> inbox;
    private final LinkedBlockingQueue<String> peerInbox;
    private final FaultState faultState;
    private volatile boolean open = true;

    private LoopbackTransport(LinkedBlockingQueue<String> inbox,
                              LinkedBlockingQueue<String> peerInbox,
                              FaultState faultState) {
        this.inbox = inbox;
        this.peerInbox = peerInbox; this.faultState = faultState;
    }

    public void setFaultInjector(FaultInjector injector) {
        faultState.injector = injector == null ? Faults.none() : injector;
    }

    @Override
    public synchronized void send(String message) {
        if (!open) {
            throw new IllegalStateException("transport closed");
        }
        Delivery delivery = new Delivery() {
            @Override
            public void now(String msg) {
                peerInbox.offer(msg);
            }

            @Override
            public void defer(String msg) {
                synchronized (faultState) {
                    faultState.deferred.add(msg);
                }
            }

            @Override
            public List<String> deferred() {
                synchronized (faultState) {
                    return new ArrayList<>(faultState.deferred);
                }
            }

            @Override
            public void releaseAll() {
                List<String> held;
                synchronized (faultState) {
                    held = new ArrayList<>(faultState.deferred);
                    faultState.deferred.clear();
                }
                held.forEach(peerInbox::offer);
            }
        };
        faultState.injector.handle(message, delivery);
    }

    @Override
    public String receive() throws Exception {
        String message = inbox.take();
        if (POISON.equals(message)) {
            inbox.offer(POISON);
            throw new EOFException("loopback transport closed");
        }
        return message;
    }

    public String receive(long timeout, TimeUnit unit) throws Exception {
        String message = inbox.poll(timeout, unit);
        if (POISON.equals(message)) {
            inbox.offer(POISON);
            throw new EOFException("loopback transport closed");
        }
        if (message == null) {
            throw new TimeoutException("no message within " + timeout + " " + unit);
        }
        return message;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        inbox.offer(POISON);
        peerInbox.offer(POISON);
    }
}
