package com.rupeek.flow.registry;

import com.rupeek.flow.activity.gen.GenTaskExecutor;
import com.rupeek.flow.activity.http.HttpTaskExecutor;
import com.rupeek.flow.activity.map.MapTaskExecutor;
import com.rupeek.flow.activity.mongo.MongoTaskExecutor;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.FlowConfigCodec;
import com.rupeek.flow.registry.store.StoredVersion;

import java.time.Clock;
import java.util.UUID;

/**
 * Publishes one task version. {@code task.version()} is authoritative, not separately passed; "publish" also
 * enforces the version-immutability {@link ConfigStore#insert} already does.
 *
 * <p>Unlike {@link FlowPublisher} (a {@code FlowDef} genuinely has no CEL — only JSONPath-style {@code in}
 * wiring), a {@code TaskDef}'s {@code fields} is exactly where every node's CEL expressions live. {@link #publish}
 * compiles them all, via the same native executor {@link #execute} would use, before anything is stored — a
 * broken expression is rejected here, not discovered the first time a live case runs it.
 */
public final class TaskPublisher {

    private final ConfigStore store;
    private final Clock clock;
    private final HttpTaskExecutor http;
    private final MongoTaskExecutor mongo;
    private final MapTaskExecutor map;
    private final GenTaskExecutor gen;

    public TaskPublisher(ConfigStore store, Clock clock, HttpTaskExecutor http, MongoTaskExecutor mongo,
                         MapTaskExecutor map, GenTaskExecutor gen) {
        this.store = store;
        this.clock = clock;
        this.http = http;
        this.mongo = mongo;
        this.map = map;
        this.gen = gen;
    }

    /**
     * @throws IllegalStateException if {@code task.taskRef()@task.version()} was already published
     * @throws com.rupeek.flow.expr.ExprCompileException if any CEL in {@code task.fields()} does not compile
     */
    public void publish(TaskDef task, String publishedBy, String reason) {
        validate(task);
        String json = FlowConfigCodec.json(task);
        String publishId = UUID.randomUUID().toString();
        store.insert(new StoredVersion(DocType.TASK, task.taskRef(), task.version(), json, FlowConfigCodec.hash(task),
                publishedBy, clock.instant(), reason, publishId));
        store.movePointer(DocType.TASK, task.taskRef(), task.version(), publishedBy, reason, publishId);
    }

    /** {@code SOAP}/{@code SFTP}/{@code CUSTOM} have no native executor yet (see {@code OperationKind}) — nothing to compile. */
    private void validate(TaskDef task) {
        switch (task.kind()) {
            case HTTP -> http.validate(task.fields());
            case MONGO -> mongo.validate(task.fields());
            case MAP -> map.validate(task.fields());
            case GEN -> gen.validate(task.fields());
            case SOAP, SFTP, CUSTOM -> { }
        }
    }
}
