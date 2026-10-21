package com.rupeek.l1.registry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rupeek.l1.engine.CompiledOperation;
import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.engine.RecipeSource;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OpRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recipes from the registry. Versions are immutable, so compiled versions are cached without expiry (bounded by
 * size); a version published after start-up is loaded and compiled on first use. The {@code latest} pointer and
 * redirects are read on every call, so a publish or a redirect takes effect at once.
 */
public final class StoreRecipeSource implements RecipeSource {

    private static final Logger LOG = LoggerFactory.getLogger(StoreRecipeSource.class);

    private final ConfigStore store;
    private final ConfigCompiler compiler;
    private final Cache<String, CompiledRecipe> recipes = Caffeine.newBuilder().maximumSize(10_000).build();
    private final Cache<OpRef, CompiledOperation> operations = Caffeine.newBuilder().maximumSize(10_000).build();

    public StoreRecipeSource(ConfigStore store, ConfigCompiler compiler) {
        this.store = store;
        this.compiler = compiler;
    }

    /**
     * Compiles every stored recipe version (and the operations they pin). Called at start-up, so a version that
     * no longer compiles (e.g. a removed function or kind) stops the worker instead of failing a case later.
     *
     * @return the number of recipe versions compiled
     */
    public int warmUp() {
        int n = 0;
        for (StoredVersion v : store.all(DocType.RECIPE)) {
            recipe(v.ref(), v.version(), false);
            n++;
        }
        if (n == 0) {
            LOG.warn("L1 registry has no published recipes yet");
        } else {
            LOG.info("L1 registry: compiled {} recipe version(s), {} operation version(s)", n,
                    operations.estimatedSize());
        }
        return n;
    }

    @Override
    public CompiledRecipe recipe(String ref, int version) {
        return recipe(ref, version, true);
    }

    private CompiledRecipe recipe(String ref, int version, boolean followRedirect) {
        int target = followRedirect
                ? store.redirect(ref, version).map(Redirect::toVersion).orElse(version)
                : version;
        if (target != version) {
            LOG.info("L1 registry: {}@{} redirected to @{}", ref, version, target);
        }
        return recipes.get(ref + "@" + target, k -> {
            StoredVersion v = store.find(DocType.RECIPE, ref, target)
                    .orElseThrow(() -> new ConfigException("unknown recipe " + ref + "@" + target));
            return compiler.compileRecipe(ConfigCodec.recipe(v), this::operation);
        });
    }

    /** Null when the operation version does not exist; the compiler reports it with the step's path. */
    private CompiledOperation operation(OpRef ref) {
        return operations.get(ref, k -> store.find(DocType.OPERATION, k.ref(), k.version())
                .map(v -> compiler.compileOperation(ConfigCodec.operation(v)))
                .orElse(null));
    }

    @Override
    public int latestVersion(String ref) {
        return store.latest(DocType.RECIPE, ref).orElseThrow(() -> new ConfigException("unknown recipe " + ref));
    }
}
