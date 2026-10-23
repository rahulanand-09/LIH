package com.rupeek.flow.registry;

import com.rupeek.flow.task.FlowMongoTestSupport;
import com.rupeek.flow.task.TestNativeExecutors;
import com.rupeek.flow.expr.ExprCompileException;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.InMemoryConfigStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TaskPublisher#publish} must compile every CEL expression in {@code TaskDef.fields} before a version is
 * allowed to land — the gap this test guards was that it didn't, at all: a {@code TaskDef} was serialised and
 * inserted directly, so a broken CEL expression was only discovered the first time a live case ran that task.
 */
class TaskPublisherValidationTest {

    private final InMemoryConfigStore store = new InMemoryConfigStore();
    private final TaskPublisher publisher;

    TaskPublisherValidationTest() {
        TestNativeExecutors.Executors executors = TestNativeExecutors.buildExecutors(
                Map.of(), FlowMongoTestSupport.client(), "lendercore", record -> { });
        publisher = new TaskPublisher(store, Clock.systemUTC(), executors.http(), executors.mongo(),
                executors.map(), executors.gen());
    }

    @Test
    void publishRejectsBrokenCelInAMapTemplateBeforeStoringAnything() {
        TaskDef broken = new TaskDef("test.broken.map", 1, OperationKind.MAP,
                Map.of("template", Map.of("outcome", "=inputs.found ? 'YES' :")));   // unbalanced ternary

        assertThatThrownBy(() -> publisher.publish(broken, "test", "bad config"))
                .isInstanceOf(ExprCompileException.class);
        assertThat(store.find(DocType.TASK, "test.broken.map", 1)).isEmpty();
    }

    @Test
    void publishRejectsBrokenCelInAnHttpOutExpressionBeforeStoringAnything() {
        TaskDef broken = new TaskDef("test.broken.http", 1, OperationKind.HTTP, Map.of(
                "resource", "core",
                "method", "GET",
                "path", "/x",
                "success", "resp.status == 200",
                "out", Map.of("id", "resp.body.(((")));   // malformed CEL

        assertThatThrownBy(() -> publisher.publish(broken, "test", "bad config"))
                .isInstanceOf(ExprCompileException.class);
        assertThat(store.find(DocType.TASK, "test.broken.http", 1)).isEmpty();
    }

    @Test
    void publishAcceptsAWellFormedMapTaskAndItLandsInTheStore() {
        TaskDef good = new TaskDef("test.good.map", 1, OperationKind.MAP,
                Map.of("template", Map.of("outcome", "=inputs.found ? 'YES' : 'NO'")));

        publisher.publish(good, "test", "fine");

        assertThat(store.find(DocType.TASK, "test.good.map", 1)).isPresent();
    }

    @Test
    void realProductionTaskConfigsAllCompile() {
        // The regression gate: every flow-config/tasks/*.json file must publish cleanly under real validation.
        TestTaskRegistry.publishAll();
    }
}
