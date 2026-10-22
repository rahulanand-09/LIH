package com.rupeek.flow.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One row for lendercore's {@code LenderApiAuditLog} collection; field names match that collection so existing
 * audit queries keep working (same contract {@code com.rupeek.l1.intercept.AuditRecord} wrote). Payloads and
 * messages are already masked and truncated.
 *
 * @param status {@code SUCCESS}, {@code BUSINESS_FAILURE}, {@code TECHNICAL_FAILURE} or {@code TIMEOUT}
 *               (lendercore's {@code ApiCallStatus})
 * @param metadata flow context: caseId, nodeId, kind, taskRef
 */
public record AuditRecord(
        String lenderSlug,
        String customerId,
        String loanRequestId,
        String loanId,
        String requestType,
        String apiEndpoint,
        String requestPayload,
        String responseCode,
        String responsePayload,
        String status,
        String errorMessage,
        long executionTimeMs,
        Instant apiInvokedAt,
        Map<String, Object> metadata) {
}
