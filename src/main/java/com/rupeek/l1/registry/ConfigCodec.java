package com.rupeek.l1.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * Turns parsed config documents into the JSON stored in the registry, and back. The hash is taken over the
 * parsed document (defaults filled in, keys sorted, {@code version} removed), so reformatting a git file does not
 * create a new version, while any change in meaning does.
 */
public final class ConfigCodec {

    // Null properties are dropped, but nulls inside maps (Mongo values, templates, env values) are kept.
    private static final ObjectMapper WRITER = ConfigJson.mapper().copy()
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.ALWAYS));

    private ConfigCodec() {
    }

    public static String json(Object doc) {
        try {
            return WRITER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new ConfigJson.ConfigException("cannot serialise config document: " + e.getOriginalMessage(), e);
        }
    }

    /** SHA-256 of the canonical form, without {@code version}. */
    public static String hash(Object doc) {
        JsonNode tree = WRITER.valueToTree(doc);
        if (tree instanceof ObjectNode o) {
            o.remove("version");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical(tree).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Recipe recipe(StoredVersion v) {
        return ConfigJson.read(v.specJson(), Recipe.class, "registry " + v.type() + " " + v.ref() + "@" + v.version());
    }

    public static OperationDef operation(StoredVersion v) {
        return ConfigJson.read(v.specJson(), OperationDef.class, "registry " + v.type() + " " + v.ref() + "@" + v.version());
    }

    public static Resource resource(StoredResource r) {
        return ConfigJson.read(r.specJson(), Resource.class, "registry resource " + r.resource());
    }

    private static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode o) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                sorted.put(k, canonical(o.get(k)));
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node instanceof ArrayNode a) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            a.forEach(e -> out.add(canonical(e)));
            return out;
        }
        return node;
    }
}
