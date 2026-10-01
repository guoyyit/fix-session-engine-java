package com.github.guoyyit.fixsessionenginejava.session;

import com.github.guoyyit.fixsessionenginejava.fix.FixException;
import com.github.guoyyit.fixsessionenginejava.fix.MessageStore;
import com.github.guoyyit.fixsessionenginejava.fix.RejectReason;

import java.util.TreeMap;

/**
 * Volatile {@link MessageStore} used when no storage path is configured.
 * Semantics match {@link com.github.guoyyit.fixsessionenginejava.fix.FileMessageStore}
 * but nothing survives a restart.
 */
final class InMemoryMessageStore implements MessageStore {

    private final Object lock = new Object();
    private final TreeMap<Integer, byte[]> outgoing = new TreeMap<>();
    private int nextSenderSeq = 1;
    private int nextTargetSeq = 1;
    private int highestDeliveredIncoming;

    @Override
    public int nextSenderSeq() {
        synchronized (lock) {
            return nextSenderSeq;
        }
    }

    @Override
    public int nextTargetSeq() {
        synchronized (lock) {
            return nextTargetSeq;
        }
    }

    @Override
    public void saveOutgoing(int seqNum, byte[] frame) {
        synchronized (lock) {
            if (seqNum != nextSenderSeq) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "outgoing seq " + seqNum + " != expected " + nextSenderSeq);
            }
            outgoing.put(seqNum, frame.clone());
            nextSenderSeq = seqNum + 1;
        }
    }

    @Override
    public byte[] loadOutgoing(int seqNum) {
        synchronized (lock) {
            byte[] frame = outgoing.get(seqNum);
            if (frame == null) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "outgoing message " + seqNum + " is not available for resend");
            }
            return frame.clone();
        }
    }

    @Override
    public int lastOutgoingSeq() {
        synchronized (lock) {
            return outgoing.isEmpty() ? 0 : outgoing.lastKey();
        }
    }

    @Override
    public void setNextTargetSeq(int seq) {
        synchronized (lock) {
            if (seq < nextTargetSeq) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "cannot move nextTargetSeq backwards: " + seq + " < " + nextTargetSeq);
            }
            nextTargetSeq = seq;
        }
    }

    @Override
    public void setNextSenderSeq(int seq) {
        synchronized (lock) {
            nextSenderSeq = seq;
        }
    }

    @Override
    public void markDelivered(int incomingSeqNum) {
        synchronized (lock) {
            if (incomingSeqNum > highestDeliveredIncoming) {
                highestDeliveredIncoming = incomingSeqNum;
            }
        }
    }

    @Override
    public boolean isDelivered(int incomingSeqNum) {
        synchronized (lock) {
            return incomingSeqNum <= highestDeliveredIncoming;
        }
    }

    @Override
    public void resetSequences(int newSenderSeq, int newTargetSeq) {
        synchronized (lock) {
            if (newSenderSeq < 1 || newTargetSeq < 1) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "reset sequences must be >= 1");
            }
            nextSenderSeq = newSenderSeq;
            nextTargetSeq = newTargetSeq;
            highestDeliveredIncoming = 0;
            outgoing.clear();
        }
    }

    @Override
    public void close() {
    }
}
