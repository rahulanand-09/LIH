package com.rupeek.l1.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One lender step: ordered steps, guards, early returns, declared outcomes and outputs.
 * Values of {@code auditKeys} and of every step's {@code when} / {@code in} / {@code return.out} are CEL.
 *
 * @param recipe    reference, {@code <lender>.<process>.<step>}
 * @param version   assigned at publish; null in git
 * @param lender    lender slug, used for audit and metrics only
 * @param resources alias to resource id, giving recipe-level expressions {@code env.<alias>.<key>}
 * @param inputs    input name to type ({@link ParamType} name)
 * @param outputs   output name to type; non-PII by contract
 * @param outcomes  every outcome the recipe can return
 */
public record Recipe(
        String recipe,
        Integer version,
        String lender,
        Map<String, String> resources,
        Map<String, String> inputs,
        Map<String, String> outputs,
        List<String> outcomes,
        Map<String, String> auditKeys,
        List<Step> steps,
        @JsonProperty("return") ReturnSpec returnSpec) {

    public Recipe {
        resources = resources == null ? Map.of() : Map.copyOf(resources);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        auditKeys = auditKeys == null ? Map.of() : Map.copyOf(auditKeys);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public Recipe withVersion(int newVersion) {
        return new Recipe(recipe, newVersion, lender, resources, inputs, outputs, outcomes, auditKeys, steps, returnSpec);
    }
}
