package com.github.guoyyit.fixsessionenginejava.fix;

import java.util.Objects;

/**
 * Identifies a FIX session: protocol version plus the ordered pair of CompIDs.
 */
public final class SessionId {
    private final String beginString;
    private final String senderCompId;
    private final String targetCompId;

    public SessionId(String beginString, String senderCompId, String targetCompId) {
        this.beginString = beginString;
        this.senderCompId = senderCompId;
        this.targetCompId = targetCompId;
    }

    public String beginString() {
        return beginString;
    }

    public String senderCompId() {
        return senderCompId;
    }

    public String targetCompId() {
        return targetCompId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionId other)) {
            return false;
        }
        return Objects.equals(beginString, other.beginString)
                && Objects.equals(senderCompId, other.senderCompId)
                && Objects.equals(targetCompId, other.targetCompId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(beginString, senderCompId, targetCompId);
    }

    @Override
    public String toString() {
        return beginString + ":" + senderCompId + "->" + targetCompId;
    }
}
