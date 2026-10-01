package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Thrown when a raw FIX message cannot be decoded.
 */
public class FixParseException extends Exception {

    private final Integer refTagId;
    private final Integer rejectReason;

    public FixParseException(String message) {
        this(message, null, null);
    }

    public FixParseException(String message, Integer refTagId, Integer rejectReason) {
        super(message);
        this.refTagId = refTagId;
        this.rejectReason = rejectReason;
    }

    public Integer getRefTagId() {
        return refTagId;
    }

    public Integer getRejectReason() {
        return rejectReason;
    }
}
