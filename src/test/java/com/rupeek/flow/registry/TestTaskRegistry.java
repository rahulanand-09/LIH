package com.rupeek.flow.registry;

import com.rupeek.flow.activity.FlowMongoTestSupport;
import com.rupeek.flow.activity.TestNativeExecutors;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.store.InMemoryConfigStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Map;

/**
 * Loads every {@code flow-config/tasks/*.json} task def and publishes it into a real (in-memory, fast)
 * {@link InMemoryConfigStore}, then hands back ready-to-register {@link FetchTaskActivityImpl}/
 * {@link ResolveTaskActivityImpl} — the task-registry counterpart of
 * {@code com.rupeek.flow.activity.TestNativeExecutors}'s role for the executor stack. No flat-map shortcut
 * exists for tasks (see docs/flow-lld.md §16) — this is the one real path every test goes through, same as
 * production.
 */
public final class TestTaskRegistry {

    public final FetchTaskActivityImpl fetchTaskActivity;
    public final ResolveTaskActivityImpl resolveTaskActivity;

    private TestTaskRegistry(FetchTaskActivityImpl fetchTaskActivity, ResolveTaskActivityImpl resolveTaskActivity) {
        this.fetchTaskActivity = fetchTaskActivity;
        this.resolveTaskActivity = resolveTaskActivity;
    }

    public static TestTaskRegistry publishAll() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        TestNativeExecutors.Executors executors = TestNativeExecutors.buildExecutors(
                Map.of(), FlowMongoTestSupport.client(), "lendercore", record -> { });
        TaskPublisher publisher = new TaskPublisher(store, Clock.systemUTC(), executors.http(), executors.mongo(),
                executors.map(), executors.gen());
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource r : resolver.getResources("classpath:flow-config/tasks/*.json")) {
                TaskDef task;
                try (var in = r.getInputStream()) {
                    task = TaskDefLoader.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                publisher.publish(task, "test", "test bootstrap");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load flow-config/tasks", e);
        }
        TaskSource source = new StoreTaskSource(store);
        return new TestTaskRegistry(new FetchTaskActivityImpl(source),
                new ResolveTaskActivityImpl(new TaskRegistryService(source)));
    }
}
