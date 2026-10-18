package com.rupeek.l1.registry;

import com.rupeek.l1.engine.RecipeSource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What L2 calls at case start: the version of each recipe to pin for the whole case. Redirects are applied when
 * the pinned version runs, so they also reach cases that are already open.
 */
public final class RegistryService {

    private final RecipeSource recipes;

    public RegistryService(RecipeSource recipes) {
        this.recipes = recipes;
    }

    /** @throws com.rupeek.l1.model.ConfigJson.ConfigException if a ref was never published */
    public Map<String, Integer> resolve(Collection<String> recipeRefs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String ref : recipeRefs) {
            out.put(ref, recipes.latestVersion(ref));
        }
        return out;
    }
}
