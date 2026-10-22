package com.rupeek.flow.model;

import java.util.Map;

/**
 * Terminal status recorded when this node is reached, plus whatever this flow hands back to its caller —
 * resolved the same way a {@code TASK}'s {@code in} is, against this flow's own completed nodes. Without this, a
 * SUBFLOW could only ever report a status label to its parent, never any data (e.g. Axis Dedupe's
 * {@code applicantId}/{@code clientLoanId}, which Timble's own audit step needs).
 */
public record EndNode(String id, String status, Map<String, String> outputs) implements Node {

    /** No outputs — the common case for a flow whose caller only needs its outcome label. */
    public EndNode(String id, String status) {
        this(id, status, Map.of());
    }
}
