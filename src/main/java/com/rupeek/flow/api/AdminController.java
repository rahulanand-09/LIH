package com.rupeek.flow.api;

import com.rupeek.flow.expr.ExprCompileException;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.TaskDefLoader;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.StoredVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Publish and inspect registry config. Bodies are the raw FlowDef / TaskDef JSON (same files as flow-config/). */
@RestController
public class AdminController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);
    private static final String BY = "api";

    private final FlowPublisher flowPublisher;
    private final TaskPublisher taskPublisher;
    private final ConfigStore store;

    public AdminController(FlowPublisher flowPublisher, TaskPublisher taskPublisher, ConfigStore store) {
        this.flowPublisher = flowPublisher;
        this.taskPublisher = taskPublisher;
        this.store = store;
    }

    @PostMapping("/admin/flows")
    public ResponseEntity<Map<String, Object>> publishFlow(@RequestBody String json,
                                                           @RequestParam(defaultValue = "published via API") String reason) {
        try {
            FlowDef flow = FlowDefLoader.parse(json);
            CaseService.require(CaseService.FLOW_REF, flow.flowRef(), "flowRef");
            flowPublisher.publish(flow, BY, reason);
            LOG.info("Admin publish accepted: flow {}@{}", flow.flowRef(), flow.version());
            return created("flowRef", flow.flowRef(), flow.version());
        } catch (RuntimeException e) {
            LOG.warn("Admin publish rejected: flow ({})", e.getClass().getSimpleName());
            throw e;
        }
    }

    @PostMapping("/admin/tasks")
    public ResponseEntity<Map<String, Object>> publishTask(@RequestBody String json,
                                                           @RequestParam(defaultValue = "published via API") String reason) {
        try {
            TaskDef task = TaskDefLoader.parse(json);
            CaseService.require(CaseService.FLOW_REF, task.taskRef(), "taskRef");
            taskPublisher.publish(task, BY, reason);   // CEL-validates before writing
            LOG.info("Admin publish accepted: task {}@{}", task.taskRef(), task.version());
            return created("taskRef", task.taskRef(), task.version());
        } catch (ExprCompileException e) {
            LOG.warn("Admin publish rejected: task CEL does not compile");
            throw new ApiException(HttpStatus.BAD_REQUEST, "CEL validation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Admin publish rejected: task ({})", e.getClass().getSimpleName());
            throw e;
        }
    }

    @GetMapping("/admin/flows/{ref}")
    public Map<String, Object> flow(@PathVariable String ref) {
        return info(DocType.FLOW, ref);
    }

    @GetMapping("/admin/tasks/{ref}")
    public Map<String, Object> task(@PathVariable String ref) {
        return info(DocType.TASK, ref);
    }

    private Map<String, Object> info(DocType type, String ref) {
        CaseService.require(CaseService.FLOW_REF, ref, "ref");
        int latest = store.latest(type, ref).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, type.name().toLowerCase() + " '" + ref + "' was never published"));
        StoredVersion v = store.find(type, ref, latest).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("latest", latest);
        out.put("publishedBy", v.publishedBy());
        out.put("publishedAt", v.publishedAt());
        out.put("specHash", v.specHash());
        return out;
    }

    private static ResponseEntity<Map<String, Object>> created(String refKey, String ref, int version) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(refKey, ref, "version", version, "published", true));
    }
}
