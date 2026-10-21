package com.rupeek.l1.model;

/**
 * A pinned operation reference, written {@code ref@version} in recipes (e.g. {@code axis.precheck.invoke@1}).
 */
public record OpRef(String ref, int version) {

    public static OpRef parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("operation reference is missing");
        }
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            throw new IllegalArgumentException("operation reference must be 'ref@version': " + value);
        }
        try {
            int version = Integer.parseInt(value.substring(at + 1));
            if (version < 1) {
                throw new IllegalArgumentException("operation version must be >= 1: " + value);
            }
            return new OpRef(value.substring(0, at), version);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("operation version is not a number: " + value, e);
        }
    }

    @Override
    public String toString() {
        return ref + "@" + version;
    }
}
