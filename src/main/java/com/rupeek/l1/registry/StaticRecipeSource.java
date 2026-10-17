package com.rupeek.l1.registry;

import com.rupeek.l1.engine.CompiledOperation;
import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.engine.RecipeSource;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipes compiled once, up front, from a fixed set of documents (the classpath config for the POC). Compiling
 * everything at start-up means a bad document stops the worker instead of failing a case later.
 */
public final class StaticRecipeSource implements RecipeSource {

    private final Map<String, CompiledRecipe> recipes = new HashMap<>();
    private final Map<String, Integer> latest = new HashMap<>();

    public StaticRecipeSource(List<Recipe> recipeDocs, List<OperationDef> operationDocs, ConfigCompiler compiler) {
        Map<OpRef, CompiledOperation> operations = new HashMap<>();
        for (OperationDef def : operationDocs) {
            CompiledOperation op = compiler.compileOperation(def);
            operations.put(op.ref(), op);
        }
        for (Recipe doc : recipeDocs) {
            CompiledRecipe recipe = compiler.compileRecipe(doc, operations::get);
            recipes.put(key(recipe.ref(), recipe.version()), recipe);
            latest.merge(recipe.ref(), recipe.version(), Math::max);
        }
    }

    @Override
    public CompiledRecipe recipe(String ref, int version) {
        CompiledRecipe r = recipes.get(key(ref, version));
        if (r == null) {
            throw new ConfigException("unknown recipe " + ref + "@" + version);
        }
        return r;
    }

    @Override
    public int latestVersion(String ref) {
        Integer v = latest.get(ref);
        if (v == null) {
            throw new ConfigException("unknown recipe " + ref);
        }
        return v;
    }

    private static String key(String ref, int version) {
        return ref + "@" + version;
    }
}
