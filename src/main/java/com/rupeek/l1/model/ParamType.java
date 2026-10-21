package com.rupeek.l1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Types usable for recipe inputs/outputs and operation parameters. Written lower-case in config. */
public enum ParamType {
    STRING,
    INT,
    DOUBLE,
    BOOL,
    MAP,
    LIST,
    DYN;

    @JsonCreator
    public static ParamType of(String value) {
        return ParamType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
