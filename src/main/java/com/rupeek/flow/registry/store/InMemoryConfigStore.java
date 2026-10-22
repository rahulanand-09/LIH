package com.rupeek.flow.registry.store;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ConfigStore} in memory: tests and standalone demos, no Mongo required. */
public final class InMemoryConfigStore implements ConfigStore {

    private final Map<String, StoredVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> pointers = new ConcurrentHashMap<>();

    private static String key(DocType type, String ref, int version) {
        return type + ":" + ref + "@" + version;
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        return Optional.ofNullable(versions.get(key(type, ref, version)));
    }

    @Override
    public OptionalInt latest(DocType type, String ref) {
        Integer v = pointers.get(type + ":" + ref);
        return v == null ? OptionalInt.empty() : OptionalInt.of(v);
    }

    @Override
    public Map<String, Integer> pointers(DocType type) {
        Map<String, Integer> out = new TreeMap<>();
        pointers.forEach((k, v) -> {
            if (k.startsWith(type + ":")) {
                out.put(k.substring(type.name().length() + 1), v);
            }
        });
        return out;
    }

    @Override
    public void insert(StoredVersion v) {
        if (versions.putIfAbsent(key(v.type(), v.ref(), v.version()), v) != null) {
            throw new IllegalStateException(v.type() + " " + v.ref() + "@" + v.version() + " already exists");
        }
    }

    @Override
    public void movePointer(DocType type, String ref, int version, String by, String reason, String publishId) {
        pointers.put(type + ":" + ref, version);
    }
}
