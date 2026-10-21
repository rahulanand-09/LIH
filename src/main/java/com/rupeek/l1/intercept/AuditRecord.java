package com.rupeek.l1.intercept;

import java.time.Instant;
import java.util.Map;

/**
 * One row for lendercore's {@code LenderApiAuditLog} collection; field names match that collection so existing
 * audit queries keep working. Payloads and messages are already masked and truncated.
 *
 * @param status {@code SUCCESS}, {@code BUSINESS_FAILURE}, {@code TECHNICAL_FAILURE} or {@code TIMEOUT}
 *               (lendercore's {@code ApiCallStatus})
 * @param metadata L1 context: caseId, recipe, recipeVersion, stepId, opRef
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
