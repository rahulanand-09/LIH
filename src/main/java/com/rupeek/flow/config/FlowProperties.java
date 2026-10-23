package com.rupeek.flow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code flow.*} settings. Off unless {@code flow.enabled=true}, so including this module never changes an
 * application that doesn't opt in.
 */
@ConfigurationProperties("flow")
public class FlowProperties {

    private boolean enabled = false;
    /** Active environment name; selects resource blocks ({@code local}, {@code qa}, {@code uat}, {@code prod}). */
    private String env = "local";
    /** Where HTTP/MONGO resource definitions are loaded from (base URL, auth, per-env values). */
    private String resourceLocation = "classpath:flow-config/resources";
    /** Mongo datasource name that the application's MongoClient is registered under. */
    private String mongoDatasource = "lendercore";
    private final Temporal temporal = new Temporal();
    private final Registry registry = new Registry();
    private final Audit audit = new Audit();

    /** Writes native HTTP/MONGO node calls to {@code LenderApiAuditLog}, same contract L1's audit writer used. */
    public static class Audit {
        private boolean enabled = true;
        private String database = "lendercore";
        private int queueCapacity = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Temporal {
        private String target = "localhost:7233";
        private String namespace = "default";
        /** Deliberately not {@code lih-l1}: a RECIPE-kind TASK node calls L1's queue explicitly either way,
         * so the flow engine's own queue can scale and deploy independently of the L1 worker. */
        private String taskQueue = "lih-l2";
        private int maxConcurrentActivities = 50;

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getTaskQueue() { return taskQueue; }
        public void setTaskQueue(String taskQueue) { this.taskQueue = taskQueue; }
        public int getMaxConcurrentActivities() { return maxConcurrentActivities; }
        public void setMaxConcurrentActivities(int maxConcurrentActivities) { this.maxConcurrentActivities = maxConcurrentActivities; }
    }

    /** Flow's own registry — a separate database from L1's {@code lih_config}, not a shared collection. */
    public static class Registry {
        /** {@code classpath}: load flows off the classpath only (dev/tests). {@code mongo}: published versions. */
        private String mode = "classpath";
        /** Connection string for the registry; empty = reuse the application's MongoClient. */
        private String uri = "";
        private String database = "lih_flow_config";
        private final Cache cache = new Cache();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public Cache getCache() { return cache; }
    }

    /** Redis read-through cache in front of {@code flow.registry.mode=mongo}. */
    public static class Cache {
        /** Off by default: the registry works Mongo-only; this is a latency optimization, not a requirement. */
        private boolean enabled = false;
        private Duration versionTtl = Duration.ofHours(1);
        private Duration pointerTtl = Duration.ofSeconds(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getVersionTtl() { return versionTtl; }
        public void setVersionTtl(Duration versionTtl) { this.versionTtl = versionTtl; }
        public Duration getPointerTtl() { return pointerTtl; }
        public void setPointerTtl(Duration pointerTtl) { this.pointerTtl = pointerTtl; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getResourceLocation() { return resourceLocation; }
    public void setResourceLocation(String resourceLocation) { this.resourceLocation = resourceLocation; }
    public String getMongoDatasource() { return mongoDatasource; }
    public void setMongoDatasource(String mongoDatasource) { this.mongoDatasource = mongoDatasource; }
    public Temporal getTemporal() { return temporal; }
    public Registry getRegistry() { return registry; }
    public Audit getAudit() { return audit; }
}
