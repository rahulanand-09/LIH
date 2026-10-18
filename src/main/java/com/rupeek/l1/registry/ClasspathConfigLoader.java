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
 * {@code resources/}. A recipe or operation without a {@code version} is version 1. Several versions of one
 * ref may sit side by side while recipes still pin the older one.
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
                Recipe doc = ConfigJson.recipe(r.getInputStream(), r.getFilename());
                recipes.add(doc.version() == null ? doc.withVersion(1) : doc);
            }
            List<OperationDef> operations = new ArrayList<>();
            for (var r : resolver.getResources(base + "/operations/**/*.json")) {
                OperationDef doc = ConfigJson.operation(r.getInputStream(), r.getFilename());
                operations.add(doc.version() == null ? doc.withVersion(1) : doc);
            }
            List<Resource> resources = new ArrayList<>();
            for (var r : resolver.getResources(base + "/resources/**/*.json")) {
                resources.add(ConfigJson.resource(r.getInputStream(), r.getFilename()));
            }
            unique(recipes.stream().map(d -> d.recipe() + "@" + d.version()).toList(), "recipe");
            unique(operations.stream().map(d -> d.op() + "@" + d.version()).toList(), "operation");
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
