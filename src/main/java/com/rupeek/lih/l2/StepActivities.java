package com.rupeek.lih.l2;

import io.temporal.activity.ActivityInterface;

import java.util.Map;

@ActivityInterface
public interface StepActivities {
    /** Runs one L1 operation through its protocol adapter and returns the extracted result. */
    Map<String, Object> call(String lenderSlug, String op, Map<String, Object> vars);

    /** PERSIST and NOTIFY: hands the flow variables to a named code sink (e.g. the PrecheckEntity writer). */
    void sink(String target, Map<String, Object> vars);
}
