package com.github.guoyyit.fixsessionenginejava.fix;

import java.util.Objects;

/**
 * Identifies a FIX session by the two CompIDs.
 */
public final class SessionId {

    private final String senderCompId;
    private final String targetCompId;

    public SessionId(String senderCompId, String targetCompId) {
        this.senderCompId = Objects.requireNonNull(senderCompId);
        this.targetCompId = Objects.requireNonNull(targetCompId);
    }

    public String getSenderCompId() {
        return senderCompId;
    }

    public String getTargetCompId() {
        return targetCompId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionId sessionId)) {
            return false;
        }
        return Objects.equals(senderCompId, sessionId.senderCompId)
                && Objects.equals(targetCompId, sessionId.targetCompId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(senderCompId, targetCompId);
    }

    @Override
    public String toString() {
        return senderCompId + "->" + targetCompId;
    }
}
