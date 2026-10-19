package com.rupeek.flow.model;

/** {@code on} is the outcome label that selects this edge; omitted (null) on edges leaving PARALLEL/JOIN. */
public record Edge(String from, String on, String to) {
}
