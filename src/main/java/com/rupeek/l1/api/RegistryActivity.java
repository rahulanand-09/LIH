package com.rupeek.l1.api;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * Version pinning for L2. Called once at case start (workflow code must not read the registry directly: it is
 * not deterministic); the returned versions go into every {@link TaskRequest#recipeVersion()} of the case.
 */
@ActivityInterface
public interface RegistryActivity {

    /**
     * @param recipeRefs every recipe the case may run, e.g. {@code axis.precheck.dedupe}
     * @return ref to the version to pin; fails with {@code L1_PERMANENT} if a ref was never published
     */
    @ActivityMethod(name = "ResolveRecipes")
    Map<String, Integer> resolveRecipes(List<String> recipeRefs);
}
