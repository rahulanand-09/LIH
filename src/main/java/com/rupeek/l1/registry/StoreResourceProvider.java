package com.rupeek.l1.registry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.Resource;

import java.time.Duration;
import java.util.function.Function;

/**
 * Resources read from the registry. Resources are live config (not pinned per case), so each one is cached only
 * for {@code ttl}: a URL move or a key rotation reaches running workers within that time.
 */
public final class StoreResourceProvider extends BaseResourceProvider {

    private final ConfigStore store;
    private final Cache<String, Resource> cache;

    public StoreResourceProvider(ConfigStore store, String envName, Function<String, String> envLookup, Duration ttl) {
        super(envName, envLookup);
        this.store = store;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1_000).build();
    }

    @Override
    public Resource resource(String id) {
        Resource r = cache.get(id, k -> store.resource(k).map(ConfigCodec::resource).orElse(null));
        if (r == null) {
            throw new ConfigJson.ConfigException("unknown resource '" + id + "'");
        }
        return r;
    }
}
