package com.rupeek.flow.resource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads every {@code *.json} resource definition under a classpath directory, e.g. {@code flow-config/resources}
 * (see {@code src/main/resources/flow-config/resources/}). Flow-native counterpart of
 * {@code com.rupeek.l1.registry.ClasspathConfigLoader}'s resource-loading half — same
 * {@code PathMatchingResourcePatternResolver} glob pattern, just one doc type instead of three (flow has no
 * separate recipe/operation registry — see the plan's decision #3).
 */
public final class ResourceConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResourceConfigLoader() {
    }

    /** @param location e.g. {@code classpath:flow-config/resources} */
    public static Map<String, com.rupeek.flow.resource.Resource> load(String location) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String base = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        Map<String, com.rupeek.flow.resource.Resource> out = new LinkedHashMap<>();
        try {
            for (Resource r : resolver.getResources(base + "/*.json")) {
                com.rupeek.flow.resource.Resource doc;
                try (var in = r.getInputStream()) {
                    doc = MAPPER.readValue(in, com.rupeek.flow.resource.Resource.class);
                }
                if (out.put(doc.resource(), doc) != null) {
                    throw new IllegalStateException("duplicate resource '" + doc.resource() + "'");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read resource config from " + location, e);
        }
        return out;
    }
}
