package com.rupeek.flow.dev;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.MongoConfigStore;

import java.time.Clock;

/**
 * Publishes one flow version into the SAME real, persistent registry {@link FlowWorkerDaemon} reads from
 * ({@code mongodb://localhost:27018/lih_flow_config}) — no need to stop/restart the daemon: {@code ResolveFlowActivity}
 * reads {@code latest} live, on every call (docs/flow-lld.md §9).
 *
 * <p>Usage: {@code FlowPublishClient /flows-v2/axis-precheck-decomposed-coreAndStableId-v2.json ops "bump to v2"}
 */
public final class FlowPublishClient {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: FlowPublishClient <classpath-resource-path> [publishedBy] [reason]");
            return;
        }
        String resourcePath = args[0];
        String publishedBy = args.length > 1 ? args[1] : "ops";
        String reason = args.length > 2 ? args[2] : "manual publish";

        MongoClient mongo = MongoClients.create("mongodb://localhost:27018");
        MongoConfigStore store = new MongoConfigStore(mongo.getDatabase("lih_flow_config")).ensureIndexes();
        FlowPublisher publisher = new FlowPublisher(store, Clock.systemUTC());

        FlowDef flow = FlowDefLoader.loadFromClasspath(resourcePath);
        publisher.publish(flow, publishedBy, reason);
        System.out.println("published " + flow.flowRef() + "@" + flow.version()
                + "; latest = " + store.latest(DocType.FLOW, flow.flowRef()));

        mongo.close();
    }

    private FlowPublishClient() {
    }
}
