package com.github.guoyyit.fixsessionenginejava.transport;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process loopback transport. Two instances are paired through
 * {@link LoopbackLink}; every frame {@link #send(byte[])} enqueues into the
 * peer's inbound queue. An optional {@link FaultPlan} drops, duplicates or
 * delays (swaps with the immediately following frame) frames deterministically
 * by direction-local frame index.
 */
public final class LoopbackTransport implements FixTransport {

    private final BlockingQueue<byte[]> inbound;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile LoopbackTransport peer;
    private volatile FaultPlan faultPlan;
    private int frameIndex;
    private byte[] heldFrame;

    LoopbackTransport(int queueCapacity) {
        this.inbound = new LinkedBlockingQueue<>(queueCapacity);
    }

    void connect(LoopbackTransport other) {
        this.peer = other;
        other.peer = this;
    }

    void setFaultPlan(FaultPlan plan) {
        this.faultPlan = plan;
    }

    @Override
    public synchronized void send(byte[] frame) throws InterruptedException {
        if (!open.get()) {
            throw new IllegalStateException("transport is closed");
        }
        FaultPlan plan = faultPlan;
        int index = ++frameIndex;
        if (plan != null && plan.shouldDrop(index)) {
            return;
        }
        LoopbackTransport target = peer;
        if (heldFrame != null) {
            byte[] previous = heldFrame;
            heldFrame = null;
            target.enqueue(frame);
            target.enqueue(previous);
            return;
        }
        if (plan != null && plan.shouldDelay(index)) {
            heldFrame = frame;
            return;
        }
        target.enqueue(frame);
        if (plan != null && plan.shouldDuplicate(index)) {
            target.enqueue(frame);
        }
    }

    private void enqueue(byte[] frame) throws InterruptedException {
        while (open.get()) {
            if (inbound.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
        throw new IllegalStateException("transport closed while enqueuing frame");
    }

    @Override
    public byte[] receive() throws InterruptedException {
        while (open.get()) {
            byte[] frame = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null) {
                return frame;
            }
        }
        throw new IllegalStateException("transport is closed");
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false) && peer != null) {
            peer.open.set(false);
        }
    }
}
