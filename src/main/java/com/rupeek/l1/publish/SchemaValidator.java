package com.rupeek.l1.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validates config documents against the JSON Schemas in {@code classpath:l1-schemas/}. The schemas are also what
 * an editor uses for completion, so they are the first thing a config author sees.
 */
public final class SchemaValidator {

    public enum DocKind {
        RECIPE("recipe"), OPERATION("operation"), RESOURCE("resource"), GOLDEN("golden");

        final String file;

        DocKind(String file) {
            this.file = file;
        }
    }

    private final Map<DocKind, JsonSchema> schemas = new EnumMap<>(DocKind.class);

    public SchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (DocKind kind : DocKind.values()) {
            String path = "l1-schemas/" + kind.file + ".schema.json";
            try (InputStream in = SchemaValidator.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("missing " + path);
                }
                schemas.put(kind, factory.getSchema(in));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path, e);
            }
        }
    }

    /** @return one line per violation, prefixed with the instance path; empty when valid */
    public List<String> validate(DocKind kind, JsonNode doc) {
        return schemas.get(kind).validate(doc).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }
}
