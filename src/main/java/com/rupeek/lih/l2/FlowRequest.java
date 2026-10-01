package com.rupeek.lih.l2;

import java.util.HashMap;
import java.util.Map;

/** Workflow input. The FlowDef travels with it, so a running flow keeps the definition it started with. */
public class FlowRequest {
    public FlowDef flow;
    public String lenderSlug;
    public Map<String, Object> vars = new HashMap<>();
}
