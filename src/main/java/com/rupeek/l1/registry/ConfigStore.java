package com.rupeek.l1.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Storage for published config. Versions are immutable: {@link #insert} refuses a {@code ref@version} that
 * already exists. Only the publish pipeline and the CLI write; the worker only reads.
 */
public interface ConfigStore {

    Optional<StoredVersion> find(DocType type, String ref, int version);

    /** Every stored version of every ref of that type (compiled at worker start-up). */
    List<StoredVersion> all(DocType type);

    /** The {@code latest} pointer of a ref, or empty when it was never published. */
    OptionalInt latest(DocType type, String ref);

    /** ref to latest version, for every ref of that type. */
    Map<String, Integer> pointers(DocType type);

    /** The newest redirect for {@code ref@fromVersion}, if any. Recipes only. */
    Optional<Redirect> redirect(String ref, int fromVersion);

    Optional<StoredResource> resource(String id);

    List<StoredResource> resources();

    /** @throws IllegalStateException if that version already exists */
    void insert(StoredVersion version);

    /** Sets {@code latest} and appends to the pointer's history. */
    void movePointer(DocType type, String ref, int version, String by, String reason, String publishId);

    /** Replaces the current document and appends it to the history. */
    void putResource(StoredResource resource);

    void addRedirect(Redirect redirect);

    /** Appends one publish attempt (successful or not) with its report. */
    void logPublish(Map<String, Object> entry);
}
