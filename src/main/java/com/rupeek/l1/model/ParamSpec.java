package com.rupeek.l1.model;

/**
 * Declares one operation parameter.
 *
 * @param pii true if the value is customer PII: masked in audit/logs and blocked from task outputs
 */
public record ParamSpec(ParamType type, boolean pii) {

    public ParamSpec {
        type = type == null ? ParamType.DYN : type;
    }
}
