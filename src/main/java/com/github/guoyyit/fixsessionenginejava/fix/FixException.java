package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Raised for codec failures, illegal transitions and unrecoverable storage errors.
 */
public class FixException extends RuntimeException {
    private final RejectReason reason;
    private final int refTag;

    public FixException(RejectReason reason) {
        this(reason, reason.description(), 0);
    }

    public FixException(RejectReason reason, String message) {
        this(reason, message, 0);
    }

    public FixException(RejectReason reason, String message, int refTag) {
        super(message);
        this.reason = reason;
        this.refTag = refTag;
    }

    public RejectReason reason() {
        return reason;
    }

    public int refTag() {
        return refTag;
    }
}
