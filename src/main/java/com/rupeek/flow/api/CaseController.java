package com.rupeek.flow.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Generic flow control surface: start a flow, signal/inspect/resume a case. No flow-specific code. */
@RestController
public class CaseController {

    private static final Logger LOG = LoggerFactory.getLogger(CaseController.class);

    public record StartRequest(Map<String, Object> inputs, String businessKey, Integer version) {
    }

    public record ResumeRequest(String nodeId, String action, String outcome) {
    }

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping("/flows/{flowRef}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String flowRef,
                                                     @RequestBody(required = false) StartRequest body) {
        LOG.info("POST /flows/{}/start", flowRef);
        StartRequest r = body == null ? new StartRequest(null, null, null) : body;
        CaseService.StartResult res = cases.start(flowRef, r.inputs(), r.businessKey(), r.version());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", res.started());
        out.put("workflowId", res.workflowId());
        out.put("runId", res.runId());
        return ResponseEntity.status(res.started() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(out);
    }

    @PostMapping("/cases/{workflowId}/events/{eventName}")
    public Map<String, Object> event(@PathVariable String workflowId, @PathVariable String eventName,
                                     @RequestBody(required = false) Map<String, Object> payload) {
        LOG.info("POST /cases/{}/events/{}", workflowId, eventName);
        cases.signal(workflowId, eventName, payload);
        return Map.of("delivered", true, "workflowId", workflowId, "event", eventName);
    }

    @GetMapping("/cases/{workflowId}")
    public Map<String, Object> status(@PathVariable String workflowId) {
        LOG.info("GET /cases/{}", workflowId);
        return cases.status(workflowId);
    }

    @PostMapping("/cases/{workflowId}/resume")
    public Map<String, Object> resume(@PathVariable String workflowId, @RequestBody ResumeRequest body) {
        LOG.info("POST /cases/{}/resume", workflowId);
        cases.resume(workflowId, body.nodeId(), body.action(), body.outcome());
        return Map.of("resumed", true, "workflowId", workflowId, "nodeId", body.nodeId());
    }
}
