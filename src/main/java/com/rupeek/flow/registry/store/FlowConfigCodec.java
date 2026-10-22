package com.rupeek.flow.registry.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * Turns a {@link com.rupeek.flow.model.FlowDef} into the JSON stored in the registry, and hashes it. Flow-native
 * counterpart of {@code com.rupeek.l1.registry.ConfigCodec} — no recipe/operation parsing needed, just the two
 * generic operations a flow publish uses. The hash is taken over the parsed document with {@code version}
 * removed and keys sorted, so reformatting a git file does not create a new version, while any change in meaning
 * does.
 */
public final class FlowConfigCodec {

    private static final ObjectMapper WRITER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.ALWAYS));

    private FlowConfigCodec() {
    }

    public static String json(Object doc) {
        try {
            return WRITER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new FlowConfigException("cannot serialise flow document: " + e.getOriginalMessage(), e);
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
