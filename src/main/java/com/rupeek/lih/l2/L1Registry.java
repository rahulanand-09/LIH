package com.rupeek.lih.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Loads every l1/*.json and flows/*.json from the classpath once. Mongo-backed store comes with real L1. */
@Component
public class L1Registry {
    private final Map<String, L1> lenders = new HashMap<>();
    private final Map<String, FlowDef> flows = new HashMap<>();   // key "{lender}/{flow}", file flows/{lender}-{flow}.json

    public L1Registry(ObjectMapper json) throws IOException {
        PathMatchingResourcePatternResolver r = new PathMatchingResourcePatternResolver();
        for (Resource res : r.getResources("classpath:l1/*.json")) {
            try (InputStream in = res.getInputStream()) {
                L1 l1 = json.readValue(in, L1.class);
                lenders.put(l1.lender, l1);
            }
        }
        for (Resource res : r.getResources("classpath:flows/*.json")) {
            try (InputStream in = res.getInputStream()) {
                String file = res.getFilename().replace(".json", "");
                int dash = file.indexOf('-');
                flows.put(file.substring(0, dash) + "/" + file.substring(dash + 1), json.readValue(in, FlowDef.class));
            }
        }
    }

    /** Flattened "lender/op" -> OperationDef, the shape StepActivitiesImpl expects. */
    public Map<String, OperationDef> operations() {
        Map<String, OperationDef> all = new HashMap<>();
        lenders.forEach((slug, l1) -> l1.operations.forEach((op, def) -> all.put(slug + "/" + op, def)));
        return all;
    }

    public FlowDef flow(String lender, String name) { return flows.get(lender + "/" + name); }

    public Map<String, L1> lenders() { return lenders; }

    /** Finds the lender that owns an inbound callback route (source_purpose), or null. */
    public Map.Entry<String, L1.Callback> callback(String key) {
        for (Map.Entry<String, L1> e : lenders.entrySet()) {
            L1.Callback c = e.getValue().callbacks.get(key);
            if (c != null) return Map.entry(e.getKey(), c);
        }
        return null;
    }
}
