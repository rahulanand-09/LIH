package com.rupeek.flow.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rupeek.flow.model.OperationKind;

import java.util.Map;

/**
 * Input to {@link TaskActivity#execute}. Recorded in Temporal history, so it carries ids and small inputs only,
 * never customer PII.
 *
 * @param kind        which operation this one primitive call performs
 * @param taskRef     shared task definition key, e.g. {@code core.fetchCustomerByLoanRequest}; null if inlined
 * @param taskVersion version pinned by the flow at its own start; 0 if inlined (no publish lifecycle)
 * @param caseId      Temporal workflow id of the (sub)flow this node runs in
 * @param nodeId      the graph node that triggered this call
 * @param runNo       run number; increments only on revert
 * @param inputs      this one operation's inputs, resolved from upstream node outputs or the flow's own input
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRequest(
        OperationKind kind,
        String taskRef,
        int taskVersion,
        String caseId,
        String nodeId,
        int runNo,
        Map<String, Object> inputs) {
}
