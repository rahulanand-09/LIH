package com.rupeek.flow.engine;

/** An operator's response to a parked node: retry it (fresh attempt budget), or skip it with a synthetic outcome. */
public record ResumeAction(String action, String outcome) {

    public static ResumeAction retry() {
        return new ResumeAction("RETRY", null);
    }

    public static ResumeAction skip(String outcome) {
        return new ResumeAction("SKIP", outcome);
    }
}
