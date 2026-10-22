package com.rupeek.flow.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rupeek.flow.model.FlowDef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads a {@link FlowDef} from a classpath JSON resource — e.g. {@code /flows/axis-precheck.json}. This is a
 * file read, not a registry: no versioning, no publish-time validation, no runtime pinning lifecycle. A real
 * published/versioned flow store (M6's {@code ResolveFlowActivity} is the seam for it) is out of scope for this
 * engine; this just proves the schema is genuinely config, not only a set of Java-constructed test fixtures.
 */
public final class FlowDefLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FlowDefLoader() {
    }

    public static FlowDef loadFromClasspath(String resourcePath) {
        try (InputStream in = FlowDefLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("No such classpath resource: " + resourcePath);
            }
            return MAPPER.readValue(in, FlowDef.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load flow from " + resourcePath, e);
        }
    }
}
