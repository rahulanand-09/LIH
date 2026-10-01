package com.rupeek.lih;

import com.rupeek.lender.dto.CommonBaseResponse;
import com.rupeek.lender.dto.sib.LenderPrecheckResponse;
import com.rupeek.lender.entity.PrecheckEntity;
import com.rupeek.lender.entity.ResponseMap;
import com.rupeek.lih.l2.FlowRequest;
import com.rupeek.lih.l2.FlowWorkflow;
import com.rupeek.lih.l2.L1;
import com.rupeek.lih.l2.L1Registry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The same three endpoints lender-core exposes for precheck (start, fetch/pull status, callback), now thin shells
 * that start or update the lender's L2 flow. Workflow id: {lender}-precheck-{customerId}-{loanRequestId}.
 */
@Slf4j
@Service
public class LihPrecheckService {
    private static final List<String> CUSTOMER_FIELDS = List.of("id", "customerProofName", "firstName", "lastName",
            "dob", "gender", "email", "customerPhone", "customerPanName", "pan", "aadhar", "customerAddress",
            "permanentAddress", "presentAddress");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_\\-]+");

    private final LihProperties props;
    private final WorkflowClient client;
    private final L1Registry l1;
    private final CoreClient core;
    private final PrecheckRepository repo;

    public LihPrecheckService(LihProperties props, WorkflowClient client, L1Registry l1, CoreClient core, PrecheckRepository repo) {
        this.props = props; this.client = client; this.l1 = l1; this.core = core; this.repo = repo;
    }

    /** POST precheck. Returns once the flow's initial calls and first persist are done, like lender-core's synchronous version. */
    public ResponseEntity<?> invoke(String loanRequestId, String lenderSlug, String loanProcess) {
        if (!props.getLenders().contains(lenderSlug)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("Lender not served by LIH: " + lenderSlug);
        }
        Optional<Map<String, Object>> customer = core.customerByLoanRequest(loanRequestId);
        if (customer.isEmpty() || customer.get().get("id") == null) {
            return new ResponseEntity<>("FAILURE", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String customerId = customer.get().get("id").toString();
        if (repo.findByLoanRequestIdAndLenderSlug(loanRequestId, lenderSlug).isPresent()) {
            log.info("PreCheck already initiated for the loanRequestId {}", loanRequestId);
            return initiated(loanRequestId);
        }
        FlowRequest req = new FlowRequest();
        req.flow = l1.flow(lenderSlug, "precheck");
        req.lenderSlug = lenderSlug;
        req.vars.put("customerId", customerId);
        req.vars.put("loanRequestId", loanRequestId);
        req.vars.put("loanProcess", loanProcess);
        req.vars.put("clientLoanId", UUID.randomUUID().toString());
        Map<String, Object> trimmed = new HashMap<>();
        CUSTOMER_FIELDS.forEach(k -> trimmed.put(k, customer.get().get(k)));
        req.vars.put("customer", trimmed);

        FlowWorkflow wf = client.newWorkflowStub(FlowWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalConfig.taskQueue(lenderSlug))
                .setWorkflowId(workflowId(lenderSlug, customerId, loanRequestId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(wf::run, req);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("PreCheck already initiated for the loanRequestId {}", loanRequestId);
            return initiated(loanRequestId);
        }
        wf.event("$ready", Map.of()); // fails (-> 500) if a CALL step exhausted its attempts, as lender-core would
        return initiated(loanRequestId);
    }

    /** GET precheck. While the Axis result is still PENDING, pull a fresh status through the flow first. */
    public ResponseEntity<?> fetch(String loanRequestId, String lenderSlug, String loanProcess) {
        Optional<PrecheckEntity> found = repo.findByLoanRequestIdAndLenderSlugAndLoanProcess(loanRequestId, lenderSlug, loanProcess);
        if (found.isEmpty()) return new ResponseEntity<>(null, HttpStatus.OK);
        PrecheckEntity e = found.get();
        if (isPending(e)) {
            try {
                client.newWorkflowStub(FlowWorkflow.class, workflowId(lenderSlug, e.getCustomerId(), loanRequestId))
                        .event("status", Map.of());
                e = repo.findByLoanRequestIdAndLenderSlugAndLoanProcess(loanRequestId, lenderSlug, loanProcess).orElse(e);
            } catch (Exception ex) {
                log.warn("No running {} flow for loanRequestId {}, returning stored state: {}", lenderSlug, loanRequestId, ex.getMessage());
            }
        }
        return new ResponseEntity<>(toResponse(e), HttpStatus.OK);
    }

    /** POST callback. Routed by source_purpose through L1; the flow decides success, we only translate it to HTTP. */
    public ResponseEntity<Map<String, String>> callback(Map<String, Object> body, String source, String purpose) {
        Map.Entry<String, L1.Callback> route = l1.callback(source + "_" + purpose.replace("'", ""));
        if (route == null) return reply(HttpStatus.NOT_FOUND, "FAILURE");
        L1.Callback cb = route.getValue();
        Object corr = body.get(cb.correlateOn);
        if (corr == null || !SAFE_ID.matcher(corr.toString()).matches()) return reply(HttpStatus.BAD_REQUEST, "FAILURE");
        String lender = route.getKey();
        String prefix = lender + "-precheck-" + corr + "-";
        String id = client.listExecutions("WorkflowId STARTS_WITH '" + prefix + "' AND ExecutionStatus = 'Running'")
                .map(m -> m.getExecution().getWorkflowId()).findFirst().orElse(null);
        if (id == null) {
            log.warn("No running {} flow for {}={}", lender, cb.correlateOn, corr);
            return reply(HttpStatus.NOT_FOUND, "FAILURE");
        }
        Map<String, Object> payload = new HashMap<>();
        cb.fields.forEach(f -> { if (body.containsKey(f)) payload.put(f, body.get(f)); });
        Map<String, Object> out = client.newWorkflowStub(FlowWorkflow.class, id).event(cb.event, payload);
        return Boolean.TRUE.equals(out.get(cb.okVar)) ? reply(HttpStatus.OK, "SUCCESS") : reply(HttpStatus.BAD_REQUEST, "FAILURE");
    }

    private static boolean isPending(PrecheckEntity e) {
        ResponseMap rm = e.getResponseMap();
        return rm == null || rm.getAxisPreCheckStatus() == null || rm.getAxisPreCheckStatus().getStatus() == null
                || "PENDING".equalsIgnoreCase(rm.getAxisPreCheckStatus().getStatus());
    }

    /** Same field-by-field copy as PrecheckService.fetchPrecheckData. */
    private static LenderPrecheckResponse toResponse(PrecheckEntity e) {
        ResponseMap rm = e.getResponseMap() != null ? e.getResponseMap() : new ResponseMap();
        LenderPrecheckResponse r = new LenderPrecheckResponse();
        r.setResponseMap(rm);
        r.setLoanRequestId(e.getLoanRequestId());
        r.setCustomerId(e.getCustomerId());
        r.setLenderSlug(e.getLenderSlug());
        r.setPrecheckStatus(e.getPrecheckStatus());
        r.setRejectionReason(e.getRejectionReason());
        r.setLenderCustomerId(e.getLenderCustomerId());
        r.setRetry(e.getRetry());
        r.setUnsecurestpstatus(e.getUnsecurestpstatus());
        r.setOverallstpstatus(e.getOverallstpstatus());
        r.setStpstatus(e.getStpstatus());
        r.setApproved(e.isApproved());
        r.setLenderstpstatus(e.getLenderstpstatus());
        r.setAutofundtransferstatus(e.getAutofundtransferstatus());
        r.setLoanProcess(e.getLoanProcess());
        return r;
    }

    private static ResponseEntity<CommonBaseResponse> initiated(String loanRequestId) {
        CommonBaseResponse r = new CommonBaseResponse();
        r.setMessage("Precheck Initiated for loan Request ID: " + loanRequestId);
        r.setStatus("SUCCESS");
        return new ResponseEntity<>(r, HttpStatus.OK);
    }

    private static ResponseEntity<Map<String, String>> reply(HttpStatus s, String status) {
        return new ResponseEntity<>(Map.of("status", status), s);
    }

    private static String workflowId(String lender, String customerId, String loanRequestId) {
        return lender + "-precheck-" + customerId + "-" + loanRequestId;
    }
}
