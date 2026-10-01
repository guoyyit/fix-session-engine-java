package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Reasons for which an inbound frame or state transition may be rejected.
 * The {@code fixCode} mirrors FIX tag 373 (SessionRejectReason) where one exists.
 */
public enum RejectReason {
    REQUIRED_TAG_MISSING(0, "Required tag missing"),
    INVALID_FIELD(5, "Value is incorrect (out of range) for this tag"),
    UNDEFINED_TAG(1, "Tag not defined for this message type"),
    CHECKSUM_MISMATCH(-1, "Checksum mismatch"),
    BODY_LENGTH_MISMATCH(-2, "BodyLength mismatch"),
    UNKNOWN_MESSAGE_TYPE(11, "Unsupported message type"),
    INVALID_STATE_TRANSITION(-3, "Illegal session state transition"),
    GAP_TOO_LARGE(-4, "Sequence gap exceeds configured bound"),
    UNRECOVERABLE_STORE(-5, "Persistent store cannot be recovered");

    private final int fixCode;
    private final String description;

    RejectReason(int fixCode, String description) {
        this.fixCode = fixCode;
        this.description = description;
    }

    public int fixCode() {
        return fixCode;
    }

    public String description() {
        return description;
    }
}
