package com.rupeek.flow.registry.store;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Read-through Redis cache in front of a {@link ConfigStore} (Mongo remains the system of record; nothing here
 * can serve a key Mongo never had). Flow-native counterpart of {@code com.rupeek.l1.registry.RedisConfigStore}.
 *
 * <ul>
 *   <li>{@link #find} — a published version is immutable, so a hit never goes stale; still TTL-bounded to cap
 *       memory, not for correctness.</li>
 *   <li>{@link #latest} — the "latest" pointer DOES change, so it's cached with a short TTL and evicted the
 *       instant {@link #movePointer} runs, so a publish is visible immediately rather than after the TTL window.</li>
 * </ul>
 *
 * <p>Writes ({@link #insert}) always go straight to the delegate — this class never decides what's durable.
 */
public final class RedisConfigStore implements ConfigStore {

    private final ConfigStore delegate;
    private final StringRedisTemplate redis;
    private final Duration versionTtl;
    private final Duration pointerTtl;

    public RedisConfigStore(ConfigStore delegate, StringRedisTemplate redis, Duration versionTtl, Duration pointerTtl) {
        this.delegate = delegate;
        this.redis = redis;
        this.versionTtl = versionTtl;
        this.pointerTtl = pointerTtl;
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        String key = versionKey(type, ref, version);
        Map<Object, Object> cached = redis.opsForHash().entries(key);
        if (!cached.isEmpty()) {
            return Optional.of(toVersion(type, cached));
        }
        Optional<StoredVersion> fromStore = delegate.find(type, ref, version);
        fromStore.ifPresent(v -> {
            redis.opsForHash().putAll(key, toHash(v));
            redis.expire(key, versionTtl);
        });
        return fromStore;
    }

    @Override
    public OptionalInt latest(DocType type, String ref) {
        String key = pointerKey(type, ref);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return OptionalInt.of(Integer.parseInt(cached));
        }
        OptionalInt fromStore = delegate.latest(type, ref);
        fromStore.ifPresent(v -> redis.opsForValue().set(key, String.valueOf(v), pointerTtl));
        return fromStore;
    }

    @Override
    public void movePointer(DocType type, String ref, int version, String by, String reason, String publishId) {
        delegate.movePointer(type, ref, version, by, reason, publishId);
        redis.delete(pointerKey(type, ref));
    }

    @Override
    public Map<String, Integer> pointers(DocType type) {
        return delegate.pointers(type);
    }

    @Override
    public void insert(StoredVersion version) {
        delegate.insert(version);
    }

    private static String versionKey(DocType type, String ref, int version) {
        return "flowcfg:" + type + ":" + ref + ":" + version;
    }

    private static String pointerKey(DocType type, String ref) {
        return "flowptr:" + type + ":" + ref;
    }

    private static Map<String, String> toHash(StoredVersion v) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("ref", v.ref());
        h.put("version", String.valueOf(v.version()));
        h.put("spec", v.specJson());
        h.put("specHash", v.specHash());
        h.put("publishedBy", nullToEmpty(v.publishedBy()));
        h.put("publishedAt", v.publishedAt() == null ? "" : v.publishedAt().toString());
        h.put("reason", nullToEmpty(v.reason()));
        h.put("publishId", nullToEmpty(v.publishId()));
        return h;
    }

    private static StoredVersion toVersion(DocType type, Map<Object, Object> h) {
        String publishedAt = (String) h.get("publishedAt");
        return new StoredVersion(type, (String) h.get("ref"), Integer.parseInt((String) h.get("version")),
                (String) h.get("spec"), (String) h.get("specHash"), emptyToNull((String) h.get("publishedBy")),
                publishedAt == null || publishedAt.isBlank() ? null : Instant.parse(publishedAt),
                emptyToNull((String) h.get("reason")), emptyToNull((String) h.get("publishId")));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
