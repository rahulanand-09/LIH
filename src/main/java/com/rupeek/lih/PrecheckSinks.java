package com.rupeek.lih;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.lender.constants.PrecheckStatus;
import com.rupeek.lender.entity.PrecheckEntity;
import com.rupeek.lender.entity.ResponseMap;
import com.rupeek.lender.entity.ResponseMapStatus;
import com.rupeek.lih.l2.StepActivitiesImpl.Sink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The code-side PERSIST/NOTIFY targets for Axis precheck. Writes the same LenderCustomerPrecheck documents,
 * with the same values, as AxisPrecheckProcessor, AxisTimbleCallbackProcessor and PrecheckService.fetchPrecheckData.
 */
@Slf4j
@Component
public class PrecheckSinks {
    private static final String AXIS = "axis";

    private final PrecheckRepository repo;
    private final RupeekWebClient rupeekWeb;
    private final ObjectMapper json;

    public PrecheckSinks(PrecheckRepository repo, RupeekWebClient rupeekWeb, ObjectMapper json) {
        this.repo = repo; this.rupeekWeb = rupeekWeb; this.json = json;
    }

    public Map<String, Sink> all() {
        Map<String, Sink> m = new HashMap<>();
        m.put("precheckInit", this::init);
        m.put("timbleStatus", this::timble);
        m.put("precheckStatus", this::status);
        m.put("rupeekweb.verified", v -> rupeekWeb.updatePrecheckVerificationStatus(loanRequestId(v), true));
        m.put("rupeekweb.failed", v -> rupeekWeb.updatePrecheckFailureResponse(loanRequestId(v), AXIS,
                repo.findByLoanRequestIdAndLenderSlug(loanRequestId(v), AXIS).orElse(null)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private void init(Map<String, Object> v) {
        String loanRequestId = loanRequestId(v);
        PrecheckEntity e = repo.findByLoanRequestIdAndLenderSlug(loanRequestId, AXIS).orElseGet(() -> {
            PrecheckEntity n = PrecheckEntity.builder().loanRequestId(loanRequestId)
                    .customerId((String) v.get("customerId")).lenderSlug(AXIS).build();
            n.setCreatedOn(new Date());
            n.setResponseMap(new ResponseMap());
            return n;
        });
        Map<String, Object> dedupe = (Map<String, Object>) v.get("dedupe");
        if (dedupe != null && Boolean.TRUE.equals(dedupe.get("valid"))) {
            e.setPrecheckStatus(PrecheckStatus.processed);
            e.setApplicantId((String) dedupe.get("applicantId"));
            e.setApproved(false);
            e.setClientLoanId((String) v.get("clientLoanId"));
            e.setLastUpdatedOn(new Date());
        } else {
            log.error("[AXIS-DEDUPE] Invalid response | LoanRequestId={} | Response={}", loanRequestId, dedupe);
        }
        Map<String, Object> timble = (Map<String, Object>) v.get("timble");
        if (timble != null) {
            e.setTimbleReferenceId((String) timble.get("referenceId"));
        } else {
            log.error("[TIMBLE-PRECHECK] Null or invalid response | LoanRequestId={}", loanRequestId);
        }
        repo.save(e);
    }

    @SuppressWarnings("unchecked")
    private void timble(Map<String, Object> v) throws Exception {
        PrecheckEntity e = repo.findByLoanRequestIdAndLenderSlug(loanRequestId(v), AXIS).orElse(null);
        if (e == null) { log.info("Precheck entity NOT found for loanRequestId={}", loanRequestId(v)); return; }
        boolean ok = Boolean.TRUE.equals(v.get("timbleOk"));
        Map<String, Object> event = (Map<String, Object>) v.get("event");
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String k : List.of("name_match", "aadhar_match", "timble_error_message")) {
            if (event.containsKey(k)) summary.put(k, event.get(k));
        }
        String summaryJson = !ok && !summary.isEmpty() ? json.writeValueAsString(summary) : "";
        ResponseMap rm = e.getResponseMap() != null ? e.getResponseMap() : new ResponseMap();
        rm.setTimble(new ResponseMapStatus(ok ? "SUCCESS" : "FAILURE",
                ok ? "Timble Precheck Successful" : "Timble Precheck Failure : " + summaryJson));
        e.setResponseMap(rm);
        e.setLastUpdatedOn(new Date());
        repo.save(e);
    }

    @SuppressWarnings("unchecked")
    private void status(Map<String, Object> v) throws Exception {
        PrecheckEntity e = repo.findByLoanRequestIdAndLenderSlug(loanRequestId(v), AXIS).orElseThrow(
                () -> new IllegalStateException("No precheck entity for " + loanRequestId(v)));
        Map<String, Object> st = (Map<String, Object>) v.get("status");
        ResponseMap rm = e.getResponseMap() != null ? e.getResponseMap() : new ResponseMap();
        rm.setPan(flag(st.get("pan")));
        rm.setHunter(flag(st.get("hunter")));
        rm.setDedupe(flag(st.get("dedupe")));
        rm.setRamp(flag(st.get("ramp")));
        ResponseMapStatus agg = new ResponseMapStatus();
        switch ((String) v.get("outcome")) {
            case "SUCCESS":
                e.setApproved(true);
                agg.setStatus("SUCCESS");
                agg.setStatusMessage("PreCheck success for the loanRequest");
                break;
            case "FAILURE":
                e.setApproved(false);
                ResponseMap detail = new ResponseMap();
                detail.setDedupe(rm.getDedupe()); detail.setPan(rm.getPan());
                detail.setRamp(rm.getRamp()); detail.setHunter(rm.getHunter()); detail.setTimble(rm.getTimble());
                agg.setStatus("FAILURE");
                agg.setStatusMessage("Precheck failure received from lender services : " + json.writeValueAsString(detail));
                break;
            default:
                agg.setStatus("PENDING");
                agg.setStatusMessage("One of the services is giving WIP");
        }
        rm.setAxisPreCheckStatus(agg);
        e.setResponseMap(rm);
        e.setLastUpdatedOn(new Date());
        repo.save(e);
    }

    /** Axis Y/WIP/N -> SUCCESS/PENDING/FAILURE, as AxisPreCheckStatus does. */
    private static ResponseMapStatus flag(Object f) {
        String s = f == null ? "" : f.toString().trim();
        String mapped = s.equalsIgnoreCase("Y") ? "SUCCESS" : s.equalsIgnoreCase("N") ? "FAILURE"
                : s.equalsIgnoreCase("WIP") ? "PENDING" : s;
        return new ResponseMapStatus(mapped, "");
    }

    private static String loanRequestId(Map<String, Object> v) { return (String) v.get("loanRequestId"); }
}
