package com.github.guoyyit.fixsessionenginejava.fix;

import java.util.List;

/**
 * Persistent per-session state and message logs.
 *
 * <p>Implementations must guarantee crash consistency: after a successful call
 * the data is durable, and recovery always restores the last consistent position.
 */
public interface MessageStore extends AutoCloseable {

    /** Next outbound sequence number to assign. */
    long getNextSenderSeqNum();

    /** Next inbound sequence number expected from the peer. */
    long getNextTargetSeqNum();

    /**
     * Whether a locally-applied sequence reset was not yet confirmed by the
     * peer. Persisted so a crash in that window can be reported as
     * unrecoverable instead of silently continuing with split sequence views.
     */
    boolean isResetPending();

    /** Persist the reset-pending marker. */
    void setResetPending(boolean pending);

    /**
     * Persist a freshly sent message under {@code seqNum} and advance
     * the next-sender pointer atomically.
     */
    void saveOutgoing(long seqNum, String rawMessage);

    /** Persist a received message under its sequence number. */
    void saveIncoming(long seqNum, String rawMessage);

    /** Advance the next-target pointer once a message is fully processed. */
    void setNextTargetSeqNum(long seqNum);

    /** Atomically set both pointers (used by an agreed sequence reset). */
    void resetSequenceNumbers(long nextSenderSeqNum, long nextTargetSeqNum);

    /** Sent messages with {@code beginSeq <= seqNum < endSeq}; 0 means open end. */
    List<String> loadOutgoing(long beginSeq, long endSeq);

    /**
     * Verify that every sent sequence number from 1 to the next-sender pointer
     * is present and readable; throws when the log is corrupt.
     */
    void verify() throws FixSessionException.Unrecoverable;

    @Override
    void close();
}
