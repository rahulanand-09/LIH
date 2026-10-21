package com.rupeek.l1.publish;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.publish.SchemaValidator.DocKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A config directory as checked into git: {@code recipes/}, {@code operations/}, {@code resources/} and
 * {@code tests/} ({@code *.golden.json}). Loading collects every problem instead of stopping at the first, so one
 * CI run reports them all.
 *
 * @param errors schema and parse errors, prefixed with the file path
 */
public record ConfigBundle(
        Path root,
        List<Doc<Recipe>> recipes,
        List<Doc<OperationDef>> operations,
        List<Doc<Resource>> resources,
        List<Doc<GoldenCase>> goldens,
        List<String> errors) {

    /** One parsed document and the file it came from (relative to the bundle root). */
    public record Doc<T>(String source, T doc) {
    }

    private static final ObjectMapper TREE = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    public static ConfigBundle load(Path root, SchemaValidator schemas) {
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            errors.add(root + ": not a directory");
            return new ConfigBundle(root, List.of(), List.of(), List.of(), List.of(), errors);
        }
        List<Doc<Recipe>> recipes = new ArrayList<>();
        for (Doc<JsonNode> d : read(root, "recipes", ".json", DocKind.RECIPE, schemas, errors)) {
            Recipe r = parse(d, Recipe.class, errors);
            if (r != null) {
                recipes.add(new Doc<>(d.source(), r.version() == null ? r.withVersion(1) : r));
            }
        }
        List<Doc<OperationDef>> operations = new ArrayList<>();
        for (Doc<JsonNode> d : read(root, "operations", ".json", DocKind.OPERATION, schemas, errors)) {
            OperationDef o = parse(d, OperationDef.class, errors);
            if (o != null) {
                operations.add(new Doc<>(d.source(), o.version() == null ? o.withVersion(1) : o));
            }
        }
        List<Doc<Resource>> resources = new ArrayList<>();
        for (Doc<JsonNode> d : read(root, "resources", ".json", DocKind.RESOURCE, schemas, errors)) {
            Resource r = parse(d, Resource.class, errors);
            if (r != null) {
                resources.add(new Doc<>(d.source(), r));
            }
        }
        List<Doc<GoldenCase>> goldens = new ArrayList<>();
        for (Doc<JsonNode> d : read(root, "tests", ".golden.json", DocKind.GOLDEN, schemas, errors)) {
            GoldenCase g = parse(d, GoldenCase.class, errors);
            if (g != null) {
                goldens.add(new Doc<>(d.source(), g));
            }
        }
        unique(recipes.stream().map(d -> d.doc().recipe() + "@" + d.doc().version()).toList(), "recipe", errors);
        unique(operations.stream().map(d -> d.doc().op() + "@" + d.doc().version()).toList(), "operation", errors);
        unique(resources.stream().map(d -> d.doc().resource()).toList(), "resource", errors);
        unique(goldens.stream().map(d -> d.doc().name()).toList(), "golden test name", errors);
        return new ConfigBundle(root, recipes, operations, resources, goldens, errors);
    }

    private static List<Doc<JsonNode>> read(Path root, String dir, String suffix, DocKind kind,
                                            SchemaValidator schemas, List<String> errors) {
        Path base = root.resolve(dir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<Doc<JsonNode>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(base)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList()) {
                String source = root.relativize(f).toString();
                JsonNode node;
                try {
                    node = TREE.readTree(f.toFile());
                } catch (IOException e) {
                    errors.add(source + ": not valid JSON: " + e.getMessage().lines().findFirst().orElse(""));
                    continue;
                }
                List<String> problems = schemas.validate(kind, node);
                if (problems.isEmpty()) {
                    out.add(new Doc<>(source, node));
                } else {
                    problems.forEach(p -> errors.add(source + ": " + p));
                }
            }
        } catch (IOException e) {
            errors.add(base + ": cannot list files: " + e.getMessage());
        }
        return out;
    }

    private static <T> T parse(Doc<JsonNode> d, Class<T> type, List<String> errors) {
        try {
            return ConfigJson.read(d.doc().toString(), type, d.source());
        } catch (ConfigJson.ConfigException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private static void unique(List<String> names, String what, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) {
                errors.add("duplicate " + what + " '" + n + "'");
            }
        }
    }
}
