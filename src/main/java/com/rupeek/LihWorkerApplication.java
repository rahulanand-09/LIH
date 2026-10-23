package com.rupeek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standing worker process: boots Mongo (registry + audit) and Redis (registry read cache) and the Temporal
 * worker for {@code com.rupeek.flow} (native HTTP/MONGO/MAP/GEN executors, no {@code com.rupeek.l1} left to
 * wire — see docs/flow-lld.md's native-executors update), wired entirely through
 * {@code FlowAutoConfiguration} and {@code application.yml} — nothing here is environment-specific. This is the
 * first real entry point for running the engine outside a test; prior demos (e.g. {@code FlowWorkerDaemon})
 * built their own Mongo/Temporal connections by hand under {@code src/test}.
 */
@SpringBootApplication
public class LihWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LihWorkerApplication.class, args);
    }
}
