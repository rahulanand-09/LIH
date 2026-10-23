package com.rupeek.flow.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code flow.api.*}: auth secrets and the callback dispatch table. */
@ConfigurationProperties("flow.api")
public class ApiProperties {

    /** Static key for /flows, /cases, /admin (header X-API-Key). Env FLOW_API_KEY. */
    private String key = "";
    /** Secret for /v1/callback (header X-Callback-Secret). Blank = callback auth disabled (WARN at startup). */
    private String callbackSecret = "";
    private final Auth auth = new Auth();
    private final CallbackStore callbackStore = new CallbackStore();
    /** Keyed by {@code source_purpose} exactly like LenderCore's bean-name pattern, e.g. YUBI_TIMBLE_STATUS. */
    private Map<String, CallbackRoute> callbacks = new LinkedHashMap<>();

    public static class Auth {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Where the precheck record is looked up (the app's business Mongo database). */
    public static class CallbackStore {
        private String database = "lendercore";
        private String collection = "LenderCustomerPrecheck";
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
    }

    /**
     * One callback route: signal {@code eventName} to case {@code workflowIdPrefix + loanRequestId}, where
     * loanRequestId is found on the precheck record matching {@code payload[customerIdField]} + {@code lenderSlug}.
     */
    public static class CallbackRoute {
        private String eventName;
        private String workflowIdPrefix;
        private String customerIdField = "customer_id";
        private String lenderSlug = "axis";
        public String getEventName() { return eventName; }
        public void setEventName(String eventName) { this.eventName = eventName; }
        public String getWorkflowIdPrefix() { return workflowIdPrefix; }
        public void setWorkflowIdPrefix(String workflowIdPrefix) { this.workflowIdPrefix = workflowIdPrefix; }
        public String getCustomerIdField() { return customerIdField; }
        public void setCustomerIdField(String customerIdField) { this.customerIdField = customerIdField; }
        public String getLenderSlug() { return lenderSlug; }
        public void setLenderSlug(String lenderSlug) { this.lenderSlug = lenderSlug; }
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String callbackSecret) { this.callbackSecret = callbackSecret; }
    public Auth getAuth() { return auth; }
    public CallbackStore getCallbackStore() { return callbackStore; }
    public Map<String, CallbackRoute> getCallbacks() { return callbacks; }
    public void setCallbacks(Map<String, CallbackRoute> callbacks) { this.callbacks = callbacks; }
}
