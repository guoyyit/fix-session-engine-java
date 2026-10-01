package com.github.guoyyit.fixsessionenginejava.session;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.MsgType;
import com.github.guoyyit.fixsessionenginejava.fix.RejectReason;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Builds FIX 4.4 administrative messages with deterministic header field
 * order: 35 (MsgType), 49 (SenderCompID), 56 (TargetCompID), 43 (PossDupFlag),
 * 97 (PossResend), 52 (SendingTime), then type-specific fields, and 34
 * (MsgSeqNum) is set by the session right before persistence.
 */
final class MessageBuilder {

    private static final DateTimeFormatter UTCTIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private MessageBuilder() {
    }

    static FixMessage base(String msgType, String senderCompId, String targetCompId,
                           long nowMillis) {
        FixMessage message = new FixMessage();
        message.set(Tag.MSG_TYPE, msgType);
        message.set(Tag.SENDER_COMP_ID, senderCompId);
        message.set(Tag.TARGET_COMP_ID, targetCompId);
        message.set(Tag.SENDING_TIME, UTCTIMESTAMP.format(Instant.ofEpochMilli(nowMillis)));
        return message;
    }

    static FixMessage logon(String senderCompId, String targetCompId, Duration heartbeat,
                            boolean resetSeqNum, long nowMillis) {
        FixMessage message = base(MsgType.LOGON, senderCompId, targetCompId, nowMillis);
        message.setInt(Tag.ENCRYPT_METHOD, 0);
        message.setInt(Tag.HEART_BT_INT, heartbeat.getSeconds());
        if (resetSeqNum) {
            message.setInt(Tag.RESET_SEQ_NUM_FLAG, 1);
        }
        return message;
    }

    static FixMessage logout(String senderCompId, String targetCompId, long nowMillis) {
        return base(MsgType.LOGOUT, senderCompId, targetCompId, nowMillis);
    }

    static FixMessage heartbeat(String senderCompId, String targetCompId,
                                String testReqId, boolean possDup, long nowMillis) {
        FixMessage message = base(MsgType.HEARTBEAT, senderCompId, targetCompId, nowMillis);
        if (testReqId != null) {
            message.set(Tag.TEST_REQ_ID, testReqId);
        }
        if (possDup) {
            message.setInt(Tag.POSS_DUP_FLAG, 1);
        }
        return message;
    }

    static FixMessage testRequest(String senderCompId, String targetCompId,
                                  String testReqId, long nowMillis) {
        FixMessage message = base(MsgType.TEST_REQUEST, senderCompId, targetCompId, nowMillis);
        message.set(Tag.TEST_REQ_ID, testReqId);
        return message;
    }

    static FixMessage resendRequest(String senderCompId, String targetCompId,
                                    int beginSeqNo, int endSeqNo, long nowMillis) {
        FixMessage message = base(MsgType.RESEND_REQUEST, senderCompId, targetCompId,
                nowMillis);
        message.setInt(Tag.BEGIN_SEQ_NO, beginSeqNo);
        message.setInt(Tag.END_SEQ_NO, endSeqNo);
        return message;
    }

    static FixMessage sequenceReset(String senderCompId, String targetCompId,
                                    int newSeqNo, boolean gapFill,
                                    boolean possDup, long nowMillis) {
        FixMessage message = base(MsgType.SEQUENCE_RESET, senderCompId, targetCompId,
                nowMillis);
        if (gapFill) {
            message.setInt(Tag.GAP_FILL_FLAG, 1);
        }
        if (possDup) {
            message.setInt(Tag.POSS_DUP_FLAG, 1);
        }
        message.setInt(Tag.NEW_SEQ_NO, newSeqNo);
        return message;
    }

    static FixMessage reject(String senderCompId, String targetCompId,
                             int refSeqNum, String refMsgType, RejectReason reason,
                             int refTagId, String text, long nowMillis) {
        FixMessage message = base(MsgType.REJECT, senderCompId, targetCompId, nowMillis);
        message.setInt(Tag.REF_SEQ_NUM, refSeqNum);
        if (refMsgType != null) {
            message.set(Tag.REF_MSG_TYPE, refMsgType);
        }
        if (reason.fixCode() >= 0) {
            message.setInt(Tag.SESSION_REJECT_REASON, reason.fixCode());
        }
        if (refTagId != 0) {
            message.setInt(Tag.REF_TAG_ID, refTagId);
        }
        message.set(Tag.TEXT, truncate(text));
        return message;
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
