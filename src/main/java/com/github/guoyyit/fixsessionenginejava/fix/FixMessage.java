package com.github.guoyyit.fixsessionenginejava.fix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Ordered collection of FIX fields excluding the session envelope tags
 * (BeginString(8), BodyLength(9), CheckSum(10)), which are produced by
 * {@link #encode(String)}.
 */
public final class FixMessage {

    private static final char SOH = '\u0001';

    private final Map<Integer, String> fields = new LinkedHashMap<>();

    public FixMessage() {
    }

    public FixMessage addField(int tag, String value) {
        if (tag <= 0) {
            throw new IllegalArgumentException("invalid tag number: " + tag);
        }
        if (value == null) {
            throw new IllegalArgumentException("value required for tag " + tag);
        }
        if (fields.containsKey(tag)) {
            throw new IllegalArgumentException("duplicate tag " + tag);
        }
        fields.put(tag, value);
        return this;
    }

    /** Replace an existing field, or add it if absent. */
    public FixMessage setField(int tag, String value) {
        fields.remove(tag);
        return addField(tag, value);
    }

    public String getString(int tag) {
        return fields.get(tag);
    }

    public int getInt(int tag) throws FixParseException {
        String value = fields.get(tag);
        if (value == null) {
            throw new FixParseException("missing tag " + tag, tag, 1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new FixParseException("tag " + tag + " is not numeric: " + value, tag, 6);
        }
    }

    public long getLong(int tag) throws FixParseException {
        String value = fields.get(tag);
        if (value == null) {
            throw new FixParseException("missing tag " + tag, tag, 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new FixParseException("tag " + tag + " is not numeric: " + value, tag, 6);
        }
    }

    public long getSeqNum() {
        String value = fields.get(FixTags.MSG_SEQ_NUM);
        return value == null ? 0 : Long.parseLong(value);
    }

    public String getMsgType() {
        return fields.get(FixTags.MSG_TYPE);
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    public List<FixField> getFields() {
        List<FixField> result = new ArrayList<>(fields.size());
        fields.forEach((tag, value) -> result.add(new FixField(tag, value)));
        return result;
    }

    public String encode(String beginString) {
        StringBuilder body = new StringBuilder();
        body.append(FixTags.MSG_TYPE).append('=').append(fields.get(FixTags.MSG_TYPE)).append(SOH);
        fields.forEach((tag, value) -> {
            if (tag != FixTags.MSG_TYPE) {
                body.append(tag).append('=').append(value).append(SOH);
            }
        });
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.US_ASCII);

        StringBuilder header = new StringBuilder();
        header.append(FixTags.BEGIN_STRING).append('=').append(beginString).append(SOH);
        header.append(FixTags.BODY_LENGTH).append('=').append(bodyBytes.length).append(SOH);
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.US_ASCII);

        int sum = 0;
        for (byte b : headerBytes) {
            sum += b & 0xFF;
        }
        for (byte b : bodyBytes) {
            sum += b & 0xFF;
        }
        int checkSum = sum % 256;

        return header + body.toString() + FixTags.CHECK_SUM + '='
                + String.format("%03d", checkSum) + SOH;
    }

    public static FixMessage decode(String raw) throws FixParseException {
        if (raw == null || raw.isEmpty()) {
            throw new FixParseException("empty message");
        }
        if (!raw.endsWith(String.valueOf(SOH))) {
            throw new FixParseException("message must end with SOH");
        }
        String[] parts = raw.substring(0, raw.length() - 1).split(String.valueOf(SOH), -1);

        FixMessage message = new FixMessage();
        int consumedHeaderBytes = 0;
        int bodyLength = -1;
        int actualBodyLength = 0;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new FixParseException("malformed field: " + part, null, 6);
            }
            int tag;
            try {
                tag = Integer.parseInt(part.substring(0, eq));
            } catch (NumberFormatException e) {
                throw new FixParseException("non-numeric tag in field: " + part, null, 0);
            }
            String value = part.substring(eq + 1);
            if (tag <= 0) {
                throw new FixParseException("invalid tag number: " + tag, tag, 0);
            }
            if (value.isEmpty()) {
                throw new FixParseException("tag " + tag + " without value", tag, 9);
            }

            if (i == 0) {
                if (tag != FixTags.BEGIN_STRING) {
                    throw new FixParseException("first field must be BeginString(8)", tag, 0);
                }
                consumedHeaderBytes += part.length() + 1;
                continue;
            }
            if (i == 1) {
                if (tag != FixTags.BODY_LENGTH) {
                    throw new FixParseException("second field must be BodyLength(9)", tag, 0);
                }
                try {
                    bodyLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new FixParseException("invalid BodyLength: " + value, tag, 6);
                }
                if (bodyLength < 0) {
                    throw new FixParseException("negative BodyLength", tag, 5);
                }
                consumedHeaderBytes += part.length() + 1;
                continue;
            }

            boolean last = i == parts.length - 1;
            if (last) {
                if (tag != FixTags.CHECK_SUM) {
                    throw new FixParseException("last field must be CheckSum(10)", tag, 0);
                }
                if (!value.matches("\\d{3}")) {
                    throw new FixParseException("invalid checksum: " + value,
                            FixTags.CHECK_SUM, 6);
                }
                int expected = expectedCheckSum(raw, part.length() + 1);
                int actual;
                try {
                    actual = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new FixParseException("invalid checksum: " + value,
                            FixTags.CHECK_SUM, 6);
                }
                if (actual != expected) {
                    throw new FixParseException("checksum mismatch: expected "
                            + String.format("%03d", expected) + " got " + value,
                            FixTags.CHECK_SUM, null);
                }
                continue;
            }

            actualBodyLength += part.length() + 1;
            try {
                message.addField(tag, value);
            } catch (IllegalArgumentException e) {
                throw new FixParseException(e.getMessage(), tag, 0);
            }
        }

        if (bodyLength < 0) {
            throw new FixParseException("missing BodyLength(9)", FixTags.BODY_LENGTH, 1);
        }
        if (actualBodyLength != bodyLength) {
            throw new FixParseException("body length mismatch: header says " + bodyLength
                    + " but body is " + actualBodyLength + " bytes",
                    FixTags.BODY_LENGTH, 5);
        }
        if (!message.has(FixTags.MSG_TYPE)) {
            throw new FixParseException("missing required tag MsgType(35)",
                    FixTags.MSG_TYPE, 1);
        }
        return message;
    }

    private static int expectedCheckSum(String raw, int trailingLength) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        int sum = 0;
        for (int i = 0; i < bytes.length - trailingLength; i++) {
            sum += bytes[i] & 0xFF;
        }
        return sum % 256;
    }
}
