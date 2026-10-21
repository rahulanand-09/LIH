package com.rupeek.l1.intercept;

import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.TransportException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.rupeek.l1.ops.OpsTestSupport.op;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterceptorsTest {

    private static final OperationDef INVOKE = op("""
            {"op": "axis.precheck.invoke", "kind": "HTTP", "resource": "axis-precheck",
             "params": {"type": {}, "body": {"type": "map", "pii": true}},
             "request": {"method": "POST", "path": "/x", "body": "=params.body"}, "success": "true",
             "piiPaths": ["$.dedupe_ids[*].id_value"]}""");

    private final List<AuditRecord> written = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private final AuditInterceptor audit = new AuditInterceptor(written::add, new PiiMasker(), clock);

    private OperationCall call(CallRecorder recorder, String requestType) {
        Map<String, Object> body = Map.of("first_name", "RAVI", "pan", "ABCDE1234F",
                "dedupe_ids", List.of(Map.of("id_type", "AADHAR", "id_value", "000011112222")));
        return new OperationCall("case-1", "axis.precheck.dedupe", 1, "axis", "call", "axis.precheck.invoke@1", INVOKE,
                requestType == null ? null : new OperationCall.AuditInfo("LR-1", "C1", requestType),
                Map.of("type", "dedupe", "body", body),
                new ExecContext(null, null, Map.of(), Map.of(), recorder));
    }

    private static OperationInterceptor.Proceed responding(CallRecorder recorder, int status, Object body) {
        return () -> {
            recorder.request("POST", "https://lender.example/x", Map.of(), Map.of(
                    "first_name", "RAVI", "pan", "ABCDE1234F",
                    "dedupe_ids", List.of(Map.of("id_type", "AADHAR", "id_value", "000011112222"))));
            recorder.response(status, body);
            return Map.of("resp", Map.of("status", status));
        };
    }

    @Test
    void chainRunsInterceptorsOutermostFirst() throws Exception {
        List<String> order = new ArrayList<>();
        OperationInterceptor a = (c, next) -> { order.add("a-in"); var r = next.proceed(); order.add("a-out"); return r; };
        OperationInterceptor b = (c, next) -> { order.add("b-in"); var r = next.proceed(); order.add("b-out"); return r; };

        new InterceptorChain(List.of(a, b)).invoke(call(new CallRecorder(), null), () -> {
            order.add("executor");
            return Map.of();
        });

        assertThat(order).containsExactly("a-in", "b-in", "executor", "b-out", "a-out");
    }

    @Test
    void auditWritesAMaskedLegacyCompatibleRecord() throws Exception {
        CallRecorder recorder = new CallRecorder();
        audit.around(call(recorder, "PRECHECK_DEDUPE"), responding(recorder, 200, Map.of("data", Map.of("applicantId", "A9"))));

        assertThat(written).hasSize(1);
        AuditRecord r = written.get(0);
        assertThat(r.lenderSlug()).isEqualTo("axis");
        assertThat(r.loanRequestId()).isEqualTo("LR-1");
        assertThat(r.customerId()).isEqualTo("C1");
        assertThat(r.requestType()).isEqualTo("PRECHECK_DEDUPE");
        assertThat(r.apiEndpoint()).isEqualTo("https://lender.example/x");
        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.responseCode()).isEqualTo("200");
        assertThat(r.apiInvokedAt()).isEqualTo(Instant.parse("2026-10-17T10:00:00Z"));
        assertThat(r.requestPayload()).contains("XXXXXX234F", "XXXXXXXX2222", "RAVI")
                .doesNotContain("ABCDE1234F", "000011112222");
        assertThat(r.responsePayload()).contains("A9");
        assertThat(r.metadata()).containsEntry("caseId", "case-1").containsEntry("stepId", "call")
                .containsEntry("opRef", "axis.precheck.invoke@1");
    }

    @Test
    void auditStatusFollowsLegacyRules() throws Exception {
        CallRecorder rec4xx = new CallRecorder();
        audit.around(call(rec4xx, "PRECHECK_DEDUPE"), responding(rec4xx, 422, null));
        CallRecorder rec5xx = new CallRecorder();
        audit.around(call(rec5xx, "PRECHECK_DEDUPE"), responding(rec5xx, 503, null));
        assertThatThrownBy(() -> audit.around(call(new CallRecorder(), "PRECHECK_DEDUPE"),
                () -> { throw new TransportException(true, true, "timed out", null); }))
                .isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> audit.around(call(new CallRecorder(), "PRECHECK_DEDUPE"),
                () -> { throw new OperationException(ErrorClass.PERMANENT, "bad config ABCDE1234F"); }))
                .isInstanceOf(OperationException.class);

        assertThat(written).extracting(AuditRecord::status)
                .containsExactly("BUSINESS_FAILURE", "TECHNICAL_FAILURE", "TIMEOUT", "TECHNICAL_FAILURE");
        assertThat(written.get(3).errorMessage()).doesNotContain("ABCDE1234F");
    }

    @Test
    void unauditedOperationsWriteNothing() throws Exception {
        CallRecorder recorder = new CallRecorder();
        audit.around(call(recorder, null), responding(recorder, 200, null));
        assertThat(written).isEmpty();
    }

    @Test
    void aFailingAuditWriterNeverFailsTheCall() throws Exception {
        AuditInterceptor broken = new AuditInterceptor(r -> { throw new IllegalStateException("db down"); },
                new PiiMasker(), clock);
        CallRecorder recorder = new CallRecorder();
        Map<String, Object> scope = broken.around(call(recorder, "PRECHECK_DEDUPE"), responding(recorder, 200, null));
        assertThat(scope).containsKey("resp");
    }

    @Test
    void metricsRecordResultTags() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsInterceptor metrics = new MetricsInterceptor(registry);
        metrics.around(call(new CallRecorder(), null), Map::of);
        assertThatThrownBy(() -> metrics.around(call(new CallRecorder(), null),
                () -> { throw new OperationException(ErrorClass.BUSINESS, "no"); })).isInstanceOf(OperationException.class);

        assertThat(registry.find("lih.l1.operation").tag("result", "ok").tag("kind", "HTTP").timer().count()).isEqualTo(1);
        assertThat(registry.find("lih.l1.operation").tag("result", "business").timer().count()).isEqualTo(1);
    }

    @Test
    void tracingSetsAndRestoresTheMdc() throws Exception {
        MDC.put("existing", "kept");
        List<String> seen = new ArrayList<>();
        new TracingInterceptor(ObservationRegistry.create()).around(call(new CallRecorder(), "PRECHECK_DEDUPE"), () -> {
            seen.add(MDC.get("caseId"));
            seen.add(MDC.get("stepId"));
            seen.add(MDC.get("loanRequestId"));
            seen.add(MDC.get("existing"));
            return Map.of();
        });
        assertThat(seen).containsExactly("case-1", "call", "LR-1", "kept");
        assertThat(MDC.get("caseId")).isNull();
        assertThat(MDC.get("existing")).isEqualTo("kept");
        MDC.clear();
    }
}
