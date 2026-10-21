package com.rupeek.l1.engine;

/**
 * Where the engine gets compiled recipes. Classpath-backed for the POC; Mongo-backed (published versions) later.
 */
public interface RecipeSource {

    /**
     * @throws com.rupeek.l1.model.ConfigJson.ConfigException if that recipe version does not exist
     */
    CompiledRecipe recipe(String ref, int version);

    /** Latest published version, used by L2 to pin versions at case start. */
    int latestVersion(String ref);
}
