package com.rupeek.lih.l2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** L2: a flow is an ordered step list plus named event handlers (callbacks, status pulls). Plain JSON. */
public class FlowDef {
    public String name;
    public List<Step> steps = new ArrayList<>();
    public Map<String, Event> events = new HashMap<>();

    public static class Step {
        public String type;      // CALL | DECIDE | PERSIST | NOTIFY | WAIT
        public String op;        // CALL: L1 operation name
        public String target;    // PERSIST/NOTIFY: named sink
        public String as;        // CALL/DECIDE: variable the result is stored under (CALL defaults to op)
        public String expr;      // DECIDE: SpEL
        public String until;     // WAIT: SpEL, flow parks until true
        public String when;      // any step: SpEL guard, skipped when false
        public Integer retries;  // CALL: max attempts, default 1 (parity with today: no retry)
        public Integer timeoutSec;
    }

    public static class Event {
        public List<Step> steps = new ArrayList<>();
        public List<String> returns = new ArrayList<>(); // variables handed back to the caller
    }
}
