package com.rupeek.l1.intercept;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one masked {@code LenderApiAuditLog} row per audited operation call (operations with an {@code audit}
 * field). Status follows lendercore's audit aspect: from the HTTP status when there is one (2xx SUCCESS, 4xx
 * BUSINESS_FAILURE, 5xx TECHNICAL_FAILURE), TIMEOUT or TECHNICAL_FAILURE when the call did not complete.
 */
public final class AuditInterceptor implements OperationInterceptor {

    static final int MAX_PAYLOAD = 10_000;
    private static final Logger LOG = LoggerFactory.getLogger(AuditInterceptor.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AuditWriter writer;
    private final PiiMasker masker;
    private final Clock clock;

    public AuditInterceptor(AuditWriter writer, PiiMasker masker, Clock clock) {
        this.writer = writer;
        this.masker = masker;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> around(OperationCall call, Proceed next) throws TransportException {
        if (call.audit() == null || call.audit().requestType() == null) {
            return next.proceed();
        }
        Instant invokedAt = clock.instant();
        long start = System.nanoTime();
        try {
            Map<String, Object> scope = next.proceed();
            record(call, statusFromResponse(call.ctx().recorder()), null, invokedAt, start);
            return scope;
        } catch (TransportException e) {
            record(call, e.timeout() ? "TIMEOUT" : "TECHNICAL_FAILURE", e.getMessage(), invokedAt, start);
            throw e;
        } catch (OperationException e) {
            record(call, e.errorClass() == ErrorClass.BUSINESS ? "BUSINESS_FAILURE" : "TECHNICAL_FAILURE",
                    e.getMessage(), invokedAt, start);
            throw e;
        } catch (RuntimeException e) {
            record(call, "TECHNICAL_FAILURE", e.getMessage(), invokedAt, start);
            throw e;
        }
    }

    private void record(OperationCall call, String status, String error, Instant invokedAt, long startNanos) {
        try {
            CallRecorder rec = call.ctx().recorder();
            List<String> piiPaths = call.def().piiPaths();
            List<String> sensitive = new ArrayList<>();
            call.def().params().forEach((name, spec) -> {
                if (spec != null && spec.pii()) {
                    PiiMasker.collectStrings(call.params().get(name), sensitive);
                }
            });
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("caseId", call.caseId());
            metadata.put("recipe", call.recipeRef());
            metadata.put("recipeVersion", call.recipeVersion());
            metadata.put("stepId", call.stepId());
            metadata.put("opRef", call.opRef());
            writer.write(new AuditRecord(
                    call.lender(),
                    call.audit().customerId(),
                    call.audit().loanRequestId(),
                    null,
                    call.audit().requestType(),
                    rec.target(),
                    truncate(json(masker.maskPayload(rec.requestBody(), piiPaths))),
                    rec.responseStatus() == null ? null : String.valueOf(rec.responseStatus()),
                    truncate(json(masker.maskPayload(rec.responseBody(), piiPaths))),
                    status,
                    masker.maskText(error, sensitive),
                    (System.nanoTime() - startNanos) / 1_000_000,
                    invokedAt,
                    metadata));
        } catch (RuntimeException e) {
            LOG.warn("audit record not built for {} {}: {}", call.opRef(), call.stepId(), e.toString());
        }
    }

    private static String statusFromResponse(CallRecorder rec) {
        Integer s = rec.responseStatus();
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
