package com.github.guoyyit.fixsessionenginejava.fix;

import java.util.Objects;

/**
 * Immutable tag/value pair.
 */
public final class FixField {

    private final int tag;
    private final String value;

    public FixField(int tag, String value) {
        this.tag = tag;
        this.value = value;
    }

    public int getTag() {
        return tag;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FixField fixField)) {
            return false;
        }
        return tag == fixField.tag && Objects.equals(value, fixField.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, value);
    }

    @Override
    public String toString() {
        return tag + "=" + value;
    }
}
