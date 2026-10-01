package com.github.guoyyit.fixsessionenginejava.fix;

import java.util.LinkedHashMap;

/**
 * A tag=value FIX message. Fields keep insertion order so encoding is deterministic.
 */
public final class FixMessage {
    private final LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();

    public FixMessage set(int tag, String value) {
        if (value == null) {
            throw new IllegalArgumentException("value for tag " + tag + " must not be null");
        }
        if (value.indexOf(SOH_STRING) >= 0) {
            throw new IllegalArgumentException("value for tag " + tag + " must not contain SOH");
        }
        fields.put(tag, value);
        return this;
    }

    public FixMessage setInt(int tag, long value) {
        return set(tag, Long.toString(value));
    }

    public String get(int tag) {
        return fields.get(tag);
    }

    public int getInt(int tag) {
        String value = fields.get(tag);
        if (value == null) {
            throw new FixException(RejectReason.REQUIRED_TAG_MISSING,
                    "missing tag " + tag, tag);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new FixException(RejectReason.INVALID_FIELD,
                    "tag " + tag + " is not an integer: " + value, tag);
        }
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    public String msgType() {
        return fields.get(Tag.MSG_TYPE);
    }

    public LinkedHashMap<Integer, String> fields() {
        return fields;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        fields.forEach((tag, value) -> builder.append(tag).append('=').append(value).append('|'));
        return builder.toString();
    }

    private static final String SOH_STRING = new String(new char[] {FixEncoder.SOH});
}
