package com.rupeek.lih.l2;

import java.util.HashMap;
import java.util.Map;

/** L1 stub: one lender's operations and inbound callback routes. Loaded from classpath l1/{lender}.json. */
public class L1 {
    public String lender;
    public Map<String, OperationDef> operations = new HashMap<>();
    public Map<String, Callback> callbacks = new HashMap<>();   // key = source_purpose, e.g. YUBI_TIMBLE_STATUS

    public static class Callback {
        public String event;        // L2 event to run
        public String correlateOn;  // payload field that identifies the customer
        public String okVar;        // event result variable: true -> 200 SUCCESS, else 400 FAILURE
        public java.util.List<String> fields = new java.util.ArrayList<>(); // payload fields passed on, others dropped
    }
}
