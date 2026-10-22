package com.rupeek.flow.audit;

import java.util.List;

/**
 * One HTTP/MONGO node call, as audited. Built by the native executor itself right after the call completes (or
 * fails) and passed to {@link TaskAuditor} — flow has no interceptor chain, so there is no {@code around()}/
 * {@code proceed()} wrapping like {@code com.rupeek.l1.intercept.OperationCall}; the executor calls
 * {@link TaskAuditor#recordSuccess}/{@link TaskAuditor#recordFailure} directly, inline.
 *
 * @param requestType value for {@code LenderApiAuditLog.requestType}; must be an existing lendercore
 *                    {@code ApiRequestType} name until lendercore adds new ones. Null means "don't audit this
 *                    call" (mirrors L1: GEN/MAP nodes with no {@code audit} field in their {@code fields} are
 *                    never audited).
 * @param responseStatus HTTP status, or null for a MONGO call / a call that never got a response
 * @param piiPaths JSONPath-with-wildcards into requestBody/responseBody to mask, same syntax as {@link PiiMasker}
 */
public record TaskCall(
        String caseId,
        String nodeId,
        String taskRef,
        String lender,
        String loanRequestId,
        String customerId,
        String requestType,
        String target,
        Object requestBody,
        Integer responseStatus,
        Object responseBody,
        List<String> piiPaths) {
}
