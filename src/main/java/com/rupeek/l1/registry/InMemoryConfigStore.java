package com.rupeek.l1.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link ConfigStore} in memory: offline validation (no registry to compare with) and tests. */
public final class InMemoryConfigStore implements ConfigStore {

    private final Map<String, StoredVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> pointers = new ConcurrentHashMap<>();
    private final Map<String, StoredResource> resources = new ConcurrentHashMap<>();
    private final List<Redirect> redirects = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> publishLog = new CopyOnWriteArrayList<>();

    private static String key(DocType type, String ref, int version) {
        return type + ":" + ref + "@" + version;
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        return Optional.ofNullable(versions.get(key(type, ref, version)));
    }

    @Override
    public List<StoredVersion> all(DocType type) {
        return versions.values().stream().filter(v -> v.type() == type)
                .sorted(Comparator.comparing(StoredVersion::ref).thenComparingInt(StoredVersion::version)).toList();
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
    public Optional<Redirect> redirect(String ref, int fromVersion) {
        Redirect found = null;
        for (Redirect r : redirects) {
            if (r.ref().equals(ref) && r.fromVersion() == fromVersion) {
                found = r;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Optional<StoredResource> resource(String id) {
        return Optional.ofNullable(resources.get(id));
    }

    @Override
    public List<StoredResource> resources() {
        return new ArrayList<>(new TreeMap<>(resources).values());
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

    @Override
    public void putResource(StoredResource resource) {
        resources.put(resource.resource(), resource);
    }

    @Override
    public void addRedirect(Redirect redirect) {
        redirects.add(redirect);
    }

    @Override
    public void logPublish(Map<String, Object> entry) {
        publishLog.add(entry);
    }

    public List<Map<String, Object>> publishLog() {
        return List.copyOf(publishLog);
    }
}
