package com.rupeek.flow.registry.store;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Storage for published flow config. Versions are immutable: {@link #insert} refuses a {@code ref@version} that
 * already exists. Flow-native counterpart of {@code com.rupeek.l1.registry.ConfigStore}, trimmed to exactly what
 * a flow publish/resolve needs — no resources, redirects or publish log, since nothing in {@code com.rupeek.flow}
 * uses those recipe-only concepts.
 */
public interface ConfigStore {

    Optional<StoredVersion> find(DocType type, String ref, int version);

    /** The {@code latest} pointer of a ref, or empty when it was never published. */
    OptionalInt latest(DocType type, String ref);

    /** ref to latest version, for every ref of that type. */
    Map<String, Integer> pointers(DocType type);

    /** @throws IllegalStateException if that version already exists */
    void insert(StoredVersion version);

    /** Sets {@code latest} and appends to the pointer's history. */
    void movePointer(DocType type, String ref, int version, String by, String reason, String publishId);
}
