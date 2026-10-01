package com.github.guoyyit.fixsessionenginejava.tests;

import com.github.guoyyit.fixsessionenginejava.fix.FixDecoder;
import com.github.guoyyit.fixsessionenginejava.fix.FixEncoder;
import com.github.guoyyit.fixsessionenginejava.fix.FixException;
import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;
import com.github.guoyyit.fixsessionenginejava.fix.RejectReason;
import com.github.guoyyit.fixsessionenginejava.fix.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodecTest {

    private final FixEncoder encoder = new FixEncoder();
    private final FixDecoder decoder = new FixDecoder();

    @Test
    void encodesAndDecodesRoundTrip() {
        FixMessage message = new FixMessage()
                .set(Tag.MSG_TYPE, "D")
                .set(Tag.SENDER_COMP_ID, "SENDER")
                .set(Tag.TARGET_COMP_ID, "TARGET")
                .setInt(Tag.MSG_SEQ_NUM, 42)
                .set(Tag.TEXT, "hello");

        byte[] frame = encoder.encode(message);
        FixMessage decoded = decoder.decode(frame);

        assertEquals("D", decoded.msgType());
        assertEquals(42, decoded.getInt(Tag.MSG_SEQ_NUM));
        assertEquals("hello", decoded.get(Tag.TEXT));

        String raw = new String(frame, StandardCharsets.US_ASCII);
        String body = "35=D\u000149=SENDER\u000156=TARGET\u000134=42\u000158=hello\u0001";
        String expected = "8=FIX.4.4\u00019=" + body.length() + "\u0001" + body;
        int checkSum = 0;
        for (byte b : expected.getBytes(StandardCharsets.US_ASCII)) {
            checkSum += b & 0xFF;
        }
        expected += "10=" + String.format("%03d", checkSum % 256) + "\u0001";
        assertEquals(expected, raw);
    }

    @Test
    void rejectsBadCheckSum() {
        byte[] frame = encoder.encode(new FixMessage()
                .set(Tag.MSG_TYPE, "0")
                .set(Tag.SENDER_COMP_ID, "A")
                .set(Tag.TARGET_COMP_ID, "B")
                .setInt(Tag.MSG_SEQ_NUM, 1));
        frame[frame.length - 4] = (byte) (frame[frame.length - 4] == '0' ? '1' : '0');
        FixException error = assertThrows(FixException.class, () -> decoder.decode(frame));
        assertEquals(RejectReason.CHECKSUM_MISMATCH, error.reason());
    }

    @Test
    void rejectsMissingMandatoryTag() {
        FixMessage message = new FixMessage()
                .set(Tag.MSG_TYPE, "0")
                .set(Tag.SENDER_COMP_ID, "A")
                .setInt(Tag.MSG_SEQ_NUM, 1);
        FixException error = assertThrows(FixException.class,
                () -> decoder.decode(encoder.encode(message)));
        assertEquals(RejectReason.REQUIRED_TAG_MISSING, error.reason());
    }

    @Test
    void rejectsMalformedField() {
        byte[] frame = "8=FIX.4.4\u00019=5\u0001XX=1\u000110=000\u0001"
                .getBytes(StandardCharsets.US_ASCII);
        FixException error = assertThrows(FixException.class, () -> decoder.decode(frame));
        assertEquals(RejectReason.INVALID_FIELD, error.reason());
    }
}
