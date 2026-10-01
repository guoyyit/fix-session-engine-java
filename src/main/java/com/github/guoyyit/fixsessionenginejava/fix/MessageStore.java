package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Persists sent messages (for resend) and the agreed sequence position,
 * so a restart resumes from the last consistent point.
 */
public interface MessageStore {

    int nextSenderSeq();

    int nextTargetSeq();

    void saveOutgoing(int seqNum, byte[] frame);

    byte[] loadOutgoing(int seqNum);

    int lastOutgoingSeq();

    void setNextTargetSeq(int seq);

    void setNextSenderSeq(int seq);

    void markDelivered(int incomingSeqNum);

    boolean isDelivered(int incomingSeqNum);

    void resetSequences(int newSenderSeq, int newTargetSeq);

    void close();
}
