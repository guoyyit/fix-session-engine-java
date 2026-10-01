package com.github.guoyyit.fixsessionenginejava.fix;

import java.nio.charset.StandardCharsets;

/**
 * Decodes raw FIX wire bytes into {@link FixMessage} instances and performs
 * structural validation (framing, BodyLength, CheckSum, mandatory tags).
 */
public final class FixDecoder {

    private static final int[] MANDATORY_HEADER_TAGS = {
            Tag.MSG_TYPE, Tag.SENDER_COMP_ID, Tag.TARGET_COMP_ID, Tag.MSG_SEQ_NUM
    };

    private final String beginString;

    public FixDecoder() {
        this("FIX.4.4");
    }

    public FixDecoder(String beginString) {
        this.beginString = beginString;
    }

    public FixMessage decode(byte[] frame) {
        if (frame == null || frame.length == 0) {
            throw new FixException(RejectReason.INVALID_FIELD, "empty frame");
        }
        String raw = new String(frame, StandardCharsets.US_ASCII);
        if (raw.charAt(raw.length() - 1) != FixEncoder.SOH) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "frame must terminate with SOH");
        }

        FixMessage message = new FixMessage();
        int bodyStart = -1;
        int bodyEnd = raw.indexOf("10=");
        if (bodyEnd < 0) {
            throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                    "missing CheckSum(10)", Tag.CHECK_SUM);
        }

        String[] fields = raw.split("\\u0001", -1);
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            if (i == fields.length - 1) {
                if (!field.isEmpty()) {
                    throw new FixException(RejectReason.INVALID_FIELD,
                            "trailing garbage after final SOH");
                }
                break;
            }
            int eq = field.indexOf('=');
            if (eq <= 0) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "malformed field: " + field);
            }
            int tag;
            try {
                tag = Integer.parseInt(field.substring(0, eq));
            } catch (NumberFormatException e) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "non-numeric tag: " + field);
            }
            String value = field.substring(eq + 1);
            if (value.isEmpty()) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "empty value for tag " + tag, tag);
            }
            if (tag <= 0) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "invalid tag number " + tag, tag);
            }
            if (message.has(tag) && tag != Tag.CHECK_SUM) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "duplicate tag " + tag, tag);
            }

            if (tag == Tag.BEGIN_STRING) {
                if (!beginString.equals(value)) {
                    throw new FixException(RejectReason.INVALID_FIELD,
                            "unexpected BeginString " + value, tag);
                }
            } else if (tag == Tag.BODY_LENGTH) {
                int declared;
                try {
                    declared = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new FixException(RejectReason.INVALID_FIELD,
                            "invalid BodyLength " + value, tag);
                }
                int actual = actualBodyLength(frame, raw, i);
                if (declared != actual) {
                    throw new FixException(RejectReason.BODY_LENGTH_MISMATCH,
                            "BodyLength declared=" + declared + " actual=" + actual, tag);
                }
                bodyStart = indexAfterThisField(raw, i);
            } else if (tag == Tag.CHECK_SUM) {
                validateCheckSum(frame, raw, value, i);
            } else {
                message.set(tag, value);
            }
        }

        if (bodyStart < 0) {
            throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                    "missing BodyLength(9)", Tag.BODY_LENGTH);
        }
        for (int tag : MANDATORY_HEADER_TAGS) {
            if (!message.has(tag)) {
                throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                        "missing mandatory tag " + tag, tag);
            }
        }
        message.getInt(Tag.MSG_SEQ_NUM);
        return message;
    }

    private static int indexAfterThisField(String raw, int fieldIndex) {
        int index = 0;
        for (int i = 0; i <= fieldIndex; i++) {
            index = raw.indexOf(FixEncoder.SOH, index) + 1;
        }
        return index;
    }

    private static int actualBodyLength(byte[] frame, String raw, int bodyLengthFieldIndex) {
        int bodyStart = indexAfterThisField(raw, bodyLengthFieldIndex);
        int trailerStart = raw.indexOf("10=", bodyStart);
        return trailerStart - bodyStart;
    }

    private static void validateCheckSum(byte[] frame, String raw, String value, int checkSumFieldIndex) {
        int declared;
        try {
            declared = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new FixException(RejectReason.CHECKSUM_MISMATCH,
                    "invalid checksum " + value, Tag.CHECK_SUM);
        }
        int checkSumFieldStart = fieldStart(raw, checkSumFieldIndex);
        int sum = 0;
        for (int i = 0; i < checkSumFieldStart; i++) {
            sum += frame[i] & 0xFF;
        }
        int actual = sum % 256;
        if (declared != actual) {
            throw new FixException(RejectReason.CHECKSUM_MISMATCH,
                    "checksum declared=" + String.format("%03d", declared)
                            + " actual=" + String.format("%03d", actual), Tag.CHECK_SUM);
        }
    }

    private static int fieldStart(String raw, int fieldIndex) {
        int index = 0;
        for (int i = 0; i < fieldIndex; i++) {
            index = raw.indexOf(FixEncoder.SOH, index) + 1;
        }
        return index;
    }
}
