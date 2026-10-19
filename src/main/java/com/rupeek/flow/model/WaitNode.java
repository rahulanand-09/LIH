package com.rupeek.flow.model;

import java.time.Duration;

/** Parks until the named external event arrives (M5). {@code timeout}/{@code onTimeoutOutcome} are optional. */
public record WaitNode(String id, String event, Duration timeout, String onTimeoutOutcome) implements Node {
}
