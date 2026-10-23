package com.rupeek.flow.api;

import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.TaskDefLoader;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.DocType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Publishes every classpath {@code flow-config/{tasks,flows}/*.json} ref that has no version yet. Never overwrites
 * (a ref with any published version is skipped), so it is idempotent. Tasks go first so each flow's refs exist.
 */
public class RegistrySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrySeeder.class);
    private static final String BY = "seeder";

    private final ConfigStore store;
    private final FlowPublisher flowPublisher;
    private final TaskPublisher taskPublisher;

    public RegistrySeeder(ConfigStore store, FlowPublisher flowPublisher, TaskPublisher taskPublisher) {
        this.store = store;
        this.flowPublisher = flowPublisher;
        this.taskPublisher = taskPublisher;
    }

    /** @return {flowsPublished, tasksPublished, skipped} */
    public int[] seed() {
        int flows = 0, tasks = 0, skipped = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath:flow-config/tasks/*.json")) {
                TaskDef task = TaskDefLoader.parse(read(r));
                if (store.latest(DocType.TASK, task.taskRef()).isPresent()) {
                    skipped++;
                } else {
                    taskPublisher.publish(task, BY, "seeded from classpath");
                    tasks++;
                }
            }
            for (Resource r : resolver.getResources("classpath:flow-config/flows/*.json")) {
                FlowDef flow = FlowDefLoader.parse(read(r));
                if (store.latest(DocType.FLOW, flow.flowRef()).isPresent()) {
                    skipped++;
                } else {
                    flowPublisher.publish(flow, BY, "seeded from classpath");
                    flows++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("registry seeding failed", e);
        }
        LOG.info("Registry seed: published {} flows, {} tasks, skipped {} already-published", flows, tasks, skipped);
        return new int[] {flows, tasks, skipped};
    }

    private static String read(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
