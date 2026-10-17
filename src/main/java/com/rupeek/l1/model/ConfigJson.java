package com.rupeek.l1.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parses config documents. Strict on purpose: an unknown or misspelt field is an error, so a typo in config
 * fails at load/publish instead of being silently ignored.
 */
public final class ConfigJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private ConfigJson() {
    }

    public static Recipe recipe(InputStream in, String source) {
        return read(in, Recipe.class, source);
    }

    public static OperationDef operation(InputStream in, String source) {
        return read(in, OperationDef.class, source);
    }

    public static Resource resource(InputStream in, String source) {
        return read(in, Resource.class, source);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static <T> T read(InputStream in, Class<T> type, String source) {
        try (in) {
            return MAPPER.readValue(in, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ConfigException(source + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new ConfigException(source + ": " + e.getMessage(), e);
        }
    }

    /** A config document could not be parsed or failed validation. */
    public static class ConfigException extends RuntimeException {
        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }

        public ConfigException(String message) {
            super(message);
        }
    }
}
