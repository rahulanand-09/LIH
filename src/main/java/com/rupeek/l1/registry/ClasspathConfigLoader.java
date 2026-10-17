package com.rupeek.l1.registry;

import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads config documents from a classpath (or file) location laid out as {@code recipes/}, {@code operations/},
 * {@code resources/}. Git files carry no version, so every recipe and operation is version 1 here; the Mongo
 * registry assigns real versions on publish.
 */
public final class ClasspathConfigLoader {

    public record LoadedConfig(List<Recipe> recipes, List<OperationDef> operations, List<Resource> resources) {
    }

    /** @param location e.g. {@code classpath:l1-config} */
    public static LoadedConfig load(String location) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String base = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        try {
            List<Recipe> recipes = new ArrayList<>();
            for (var r : resolver.getResources(base + "/recipes/**/*.json")) {
                recipes.add(ConfigJson.recipe(r.getInputStream(), r.getFilename()).withVersion(1));
            }
            List<OperationDef> operations = new ArrayList<>();
            for (var r : resolver.getResources(base + "/operations/**/*.json")) {
                operations.add(ConfigJson.operation(r.getInputStream(), r.getFilename()).withVersion(1));
            }
            List<Resource> resources = new ArrayList<>();
            for (var r : resolver.getResources(base + "/resources/**/*.json")) {
                resources.add(ConfigJson.resource(r.getInputStream(), r.getFilename()));
            }
            unique(recipes.stream().map(Recipe::recipe).toList(), "recipe");
            unique(operations.stream().map(OperationDef::op).toList(), "operation");
            unique(resources.stream().map(Resource::resource).toList(), "resource");
            return new LoadedConfig(recipes, operations, resources);
        } catch (IOException e) {
            throw new ConfigException("cannot read config from " + location + ": " + e.getMessage(), e);
        }
    }

    private static void unique(List<String> names, String what) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) {
                throw new ConfigException("duplicate " + what + " '" + n + "'");
            }
        }
    }

    private ClasspathConfigLoader() {
    }
}
