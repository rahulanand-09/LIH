package com.rupeek.flow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes one masked {@code LenderApiAuditLog} row per audited node call. Flow-native counterpart of
 * {@code com.rupeek.l1.intercept.AuditInterceptor}, called directly by the native HTTP/MONGO executors right
 * after a call completes or fails — there is no interceptor chain to wrap, so callers invoke
 * {@link #recordSuccess}/{@link #recordFailure} explicitly instead of an {@code around()} method. A call whose
 * {@link TaskCall#requestType()} is null is silently not audited (same as L1: GEN/MAP ops with no {@code audit}
 * field never write a row).
 */
public final class TaskAuditor {

    static final int MAX_PAYLOAD = 10_000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AuditWriter writer;
    private final PiiMasker masker;
    private final Clock clock;

    public TaskAuditor(AuditWriter writer, PiiMasker masker, Clock clock) {
        this.writer = writer;
        this.masker = masker;
        this.clock = clock;
    }

    /** Call after a call that got a real response (HTTP of any status, or a completed Mongo op). */
    public void recordSuccess(TaskCall call, Instant invokedAt, long executionTimeMs) {
        record(call, statusFromResponse(call.responseStatus()), null, invokedAt, executionTimeMs);
    }

    /**
     * Call when the operation threw before producing a usable response (timeout, transport error, Mongo
     * exception, business-rule failure with no response body worth logging, etc).
     *
     * @param status            {@code TIMEOUT}, {@code TECHNICAL_FAILURE} or {@code BUSINESS_FAILURE}
     * @param sensitiveValues   values to scrub out of {@code errorMessage} (e.g. this call's PII-marked inputs)
     */
    public void recordFailure(TaskCall call, String status, String errorMessage, Collection<String> sensitiveValues,
                               Instant invokedAt, long executionTimeMs) {
        record(call, status, masker.maskText(errorMessage, sensitiveValues), invokedAt, executionTimeMs);
    }

    private void record(TaskCall call, String status, String maskedError, Instant invokedAt, long executionTimeMs) {
        if (call.requestType() == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("caseId", call.caseId());
        metadata.put("nodeId", call.nodeId());
        metadata.put("taskRef", call.taskRef());
        writer.write(new AuditRecord(
                call.lender(),
                call.customerId(),
                call.loanRequestId(),
                null,
                call.requestType(),
                call.target(),
                truncate(json(masker.maskPayload(call.requestBody(), call.piiPaths()))),
                call.responseStatus() == null ? null : String.valueOf(call.responseStatus()),
                truncate(json(masker.maskPayload(call.responseBody(), call.piiPaths()))),
                status,
                maskedError,
                executionTimeMs,
                invokedAt,
                metadata));
    }

    private static String statusFromResponse(Integer s) {
        if (s == null || (s >= 200 && s < 300)) {
            return "SUCCESS";
        }
        if (s >= 400 && s < 500) {
            return "BUSINESS_FAILURE";
        }
        if (s >= 500) {
            return "TECHNICAL_FAILURE";
        }
        return "SUCCESS";
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "Serialization failed: " + e.getMessage();
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_PAYLOAD ? s : s.substring(0, MAX_PAYLOAD) + "... [TRUNCATED]";
    }
}
