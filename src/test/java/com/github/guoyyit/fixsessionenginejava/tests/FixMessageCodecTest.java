package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.FixParseException;
import com.github.guoyyit.fixsessionenginejava.fix.FixTags;
import com.github.guoyyit.fixsessionenginejava.fix.MsgType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixMessageCodecTest {

    private static final char SOH = '\u0001';

    private static FixMessage sample() {
        return new FixMessage()
                .addField(FixTags.MSG_TYPE, MsgType.LOGON)
                .addField(FixTags.SENDER_COMP_ID, "INIT")
                .addField(FixTags.TARGET_COMP_ID, "ACCEPT")
                .addField(FixTags.MSG_SEQ_NUM, "1")
                .addField(FixTags.SENDING_TIME, "20260101-00:00:00.000")
                .addField(FixTags.HEART_BT_INT, "10");
    }

    private static String frame(String body) {
        String header = "8=FIX.4.4" + SOH + "9=" + body.length() + SOH;
        int sum = 0;
        for (byte b : (header + body).getBytes(StandardCharsets.US_ASCII)) {
            sum += b & 0xFF;
        }
        return header + body + "10=" + String.format("%03d", sum % 256) + SOH;
    }

    @Test
    void encodesBodyLengthAndCheckSum() {
        String raw = sample().encode("FIX.4.4");
        assertTrue(raw.startsWith("8=FIX.4.4" + SOH));

        String body = raw.substring(raw.indexOf("9=") + 2);
        int soh = body.indexOf(SOH);
        int bodyLength = Integer.parseInt(body.substring(0, soh));
        int bodyStart = raw.indexOf(SOH, raw.indexOf("9=")) + 1;
        int checksumStart = raw.lastIndexOf(SOH, raw.length() - 2);
        assertEquals(checksumStart - bodyStart + 1, bodyLength);
    }

    @Test
    void roundTripsFieldsInOrder() throws FixParseException {
        String raw = sample().encode("FIX.4.4");
        FixMessage decoded = FixMessage.decode(raw);
        assertEquals(MsgType.LOGON, decoded.getMsgType());
        assertEquals(1, decoded.getSeqNum());
        assertEquals("INIT", decoded.getString(FixTags.SENDER_COMP_ID));
        assertEquals(6, decoded.getFields().size());
        assertEquals(FixTags.MSG_TYPE, decoded.getFields().get(0).getTag());
    }

    @Test
    void rejectsChecksumError() {
        char[] raw = sample().encode("FIX.4.4").toCharArray();
        int idx = new String(raw).lastIndexOf("10=") + 3;
        char good = raw[idx];
        raw[idx] = good == '0' ? '1' : '0';
        FixParseException error = assertThrows(FixParseException.class,
                () -> FixMessage.decode(new String(raw)));
        assertTrue(error.getMessage().contains("checksum"));
        assertEquals(FixTags.CHECK_SUM, error.getRefTagId());
    }

    @Test
    void rejectsWrongBodyLength() {
        String raw = sample().encode("FIX.4.4");
        String tampered = raw.replaceFirst("9=\\d+", "9=999");
        assertThrows(FixParseException.class, () -> FixMessage.decode(tampered));
    }

    @Test
    void rejectsMalformedField() {
        String raw = "8=FIX.4.4" + SOH + "9=20" + SOH + "garbage" + SOH
                + "10=000" + SOH;
        assertThrows(FixParseException.class, () -> FixMessage.decode(raw));
    }

    @Test
    void rejectsNonNumericTag() {
        String raw = "8=FIX.4.4" + SOH + "9=20" + SOH + "XX=Y" + SOH
                + "10=000" + SOH;
        assertThrows(FixParseException.class, () -> FixMessage.decode(raw));
    }

    @Test
    void rejectsDuplicateTag() {
        FixMessage message = new FixMessage().addField(FixTags.MSG_TYPE, "0");
        assertThrows(IllegalArgumentException.class,
                () -> message.addField(FixTags.MSG_TYPE, "1"));
    }

    @Test
    void rejectsMissingMsgType() throws Exception {
        String raw = frame("49=A" + SOH);
        FixParseException error = assertThrows(FixParseException.class,
                () -> FixMessage.decode(raw));
        assertEquals(FixTags.MSG_TYPE, error.getRefTagId());
    }

    @Test
    void acceptsValidMessageWithNoThrow() {
        assertDoesNotThrow(() -> FixMessage.decode(sample().encode("FIX.4.4")));
    }
}
