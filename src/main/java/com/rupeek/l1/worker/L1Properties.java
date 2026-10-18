package com.rupeek.l1.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code l1.*} settings. The worker is off unless {@code l1.enabled=true}, so it never changes an application that
 * only includes it.
 */
@ConfigurationProperties("l1")
public class L1Properties {

    /** Turns the L1 worker on. */
    private boolean enabled = false;
    /** Active environment name; selects resource blocks ({@code local}, {@code qa}, {@code uat}, {@code prod}). */
    private String env = "local";
    /** Where recipes, operations and resources are loaded from. */
    private String configLocation = "classpath:l1-config";
    /** Mongo datasource name that the application's MongoClient is registered under. */
    private String mongoDatasource = "lendercore";
    private final Temporal temporal = new Temporal();
    private final Audit audit = new Audit();
    private final Dev dev = new Dev();

    public static class Temporal {
        /** Start a Temporal worker polling the task queue. Off in tests that only need the components. */
        private boolean enabled = true;
        private String target = "localhost:7233";
        private String namespace = "default";
        private String taskQueue = "lih-l1";
        private int maxConcurrentActivities = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getTaskQueue() { return taskQueue; }
        public void setTaskQueue(String taskQueue) { this.taskQueue = taskQueue; }
        public int getMaxConcurrentActivities() { return maxConcurrentActivities; }
        public void setMaxConcurrentActivities(int maxConcurrentActivities) { this.maxConcurrentActivities = maxConcurrentActivities; }
    }

    public static class Audit {
        /** Write audit rows to LenderApiAuditLog. */
        private boolean enabled = true;
        /** Database holding LenderApiAuditLog. */
        private String database = "lendercore";
        private int queueCapacity = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Dev {
        /** Register the dev-only {@code L1Probe} workflow on the L1 task queue, to run {@code RunTask} without L2. */
        private boolean probeWorkflow = false;

        public boolean isProbeWorkflow() { return probeWorkflow; }
        public void setProbeWorkflow(boolean probeWorkflow) { this.probeWorkflow = probeWorkflow; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getConfigLocation() { return configLocation; }
    public void setConfigLocation(String configLocation) { this.configLocation = configLocation; }
    public String getMongoDatasource() { return mongoDatasource; }
    public void setMongoDatasource(String mongoDatasource) { this.mongoDatasource = mongoDatasource; }
    public Temporal getTemporal() { return temporal; }
    public Audit getAudit() { return audit; }
    public Dev getDev() { return dev; }
}
