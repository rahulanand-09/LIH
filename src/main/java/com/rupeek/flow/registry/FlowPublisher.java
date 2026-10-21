package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;
import com.rupeek.l1.registry.ConfigCodec;
import com.rupeek.l1.registry.ConfigStore;
import com.rupeek.l1.registry.DocType;
import com.rupeek.l1.registry.StoredVersion;

import java.time.Clock;
import java.util.UUID;

/**
 * Publishes one flow version. Deliberately NOT a mirror of L1's real {@code Publisher} — that class's job is
 * CEL-compiling every recipe/operation and running golden tests before a version is allowed to land; a
 * {@code FlowDef} has no CEL to compile, so validating one is just "does it parse" (done by the time a caller
 * has a {@code FlowDef} in hand at all) plus the version-immutability {@link ConfigStore#insert} already
 * enforces (throws on a duplicate {@code ref@version}). Nothing here is a placeholder — this is the real publish
 * path, just a much smaller one than a recipe's.
 */
public final class FlowPublisher {

    private final ConfigStore store;
    private final Clock clock;

    public FlowPublisher(ConfigStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * {@code flow.version()} is authoritative (same convention a recipe's own declared {@code version} field
     * already follows) — not a separately-passed number, so the stored row and the spec's own text can never
     * disagree on what version this is.
     *
     * @throws IllegalStateException if {@code flow.flowRef()@flow.version()} was already published
     */
    public void publish(FlowDef flow, String publishedBy, String reason) {
        String json = ConfigCodec.json(flow);
        String publishId = UUID.randomUUID().toString();
        store.insert(new StoredVersion(DocType.FLOW, flow.flowRef(), flow.version(), json, ConfigCodec.hash(flow),
                publishedBy, clock.instant(), reason, publishId));
        store.movePointer(DocType.FLOW, flow.flowRef(), flow.version(), publishedBy, reason, publishId);
    }
}
