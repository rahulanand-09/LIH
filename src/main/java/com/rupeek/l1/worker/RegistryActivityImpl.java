package com.rupeek.l1.worker;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.RegistryActivity;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.registry.RegistryService;
import io.temporal.failure.ApplicationFailure;

import java.util.List;
import java.util.Map;

/** {@code ResolveRecipes}: the latest published version of each recipe. An unknown ref is a config error. */
public final class RegistryActivityImpl implements RegistryActivity {

    private final RegistryService registry;

    public RegistryActivityImpl(RegistryService registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Integer> resolveRecipes(List<String> recipeRefs) {
        try {
            return registry.resolve(recipeRefs);
        } catch (ConfigException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), L1FailureType.L1_PERMANENT.name());
        }
    }
}
