package com.rupeek.flow.model;

/** {@code then} is {@code "PARK"} only, for now (M3). */
public record OnError(int retries, String then) {
}
