package com.rupeek.flow.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.flow.model.TaskDef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads a {@link TaskDef} from a classpath JSON resource — e.g. {@code /flow-config/tasks/gen.stableId.json} —
 * or parses one read back out of the registry. Mirrors {@link FlowDefLoader}.
 */
public final class TaskDefLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TaskDefLoader() {
    }

    public static TaskDef loadFromClasspath(String resourcePath) {
        try (InputStream in = TaskDefLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("No such classpath resource: " + resourcePath);
            }
            return MAPPER.readValue(in, TaskDef.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load task from " + resourcePath, e);
        }
    }

    public static TaskDef parse(String json) {
        try {
            return MAPPER.readValue(json, TaskDef.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse task JSON", e);
        }
    }
}
