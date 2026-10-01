package com.github.guoyyit.fixsessionenginejava.transport;

/**
 * A pair of connected in-process transports with independent per-direction
 * fault injection, simulating a local loopback network between two FIX
 * endpoints.
 */
public final class LoopbackLink {

    private final LoopbackTransport endpointA;
    private final LoopbackTransport endpointB;

    public LoopbackLink() {
        this(4096);
    }

    public LoopbackLink(int queueCapacity) {
        endpointA = new LoopbackTransport(queueCapacity);
        endpointB = new LoopbackTransport(queueCapacity);
        endpointA.connect(endpointB);
    }

    public FixTransport endpointA() {
        return endpointA;
    }

    public FixTransport endpointB() {
        return endpointB;
    }

    /** Faults applied to frames A sends toward B. */
    public void setFaultPlanAtoB(FaultPlan plan) {
        endpointA.setFaultPlan(plan);
    }

    /** Faults applied to frames B sends toward A. */
    public void setFaultPlanBtoA(FaultPlan plan) {
        endpointB.setFaultPlan(plan);
    }
}
