package com.github.guoyyit.fixsessionenginejava.session;

/**
 * Raised when an outbound message cannot be accepted because the bounded
 * send queue is full (slow consumer). Behaviour is deterministic and
 * documented in SESSION_ENGINE.md.
 */
public final class OutboundRejectedException extends RuntimeException {
    public OutboundRejectedException(String message) {
        super(message);
    }
}
