package com.rupeek.l1.worker;

import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.RecipeSource;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.registry.RegistryService;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RegistryActivityImplTest {

    private final RegistryActivityImpl activity = new RegistryActivityImpl(new RegistryService(new RecipeSource() {
        @Override
        public CompiledRecipe recipe(String ref, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int latestVersion(String ref) {
            if (ref.startsWith("axis.")) {
                return 3;
            }
            throw new ConfigException("unknown recipe " + ref);
        }
    }));

    @Test
    void pinsTheLatestVersions() {
        assertThat(activity.resolveRecipes(List.of("axis.precheck.dedupe", "axis.precheck.status")))
                .isEqualTo(Map.of("axis.precheck.dedupe", 3, "axis.precheck.status", 3));
    }

    @Test
    void anUnknownRecipeIsAPermanentFailure() {
        ApplicationFailure f = catchThrowableOfType(ApplicationFailure.class,
                () -> activity.resolveRecipes(List.of("nope.recipe")));
        assertThat(f.getType()).isEqualTo("L1_PERMANENT");
        assertThat(f.isNonRetryable()).isTrue();
    }
}
