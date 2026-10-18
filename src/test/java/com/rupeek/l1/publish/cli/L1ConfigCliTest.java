package com.rupeek.l1.publish.cli;

import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.registry.DocType;
import com.rupeek.l1.registry.InMemoryConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class L1ConfigCliTest {

    private final InMemoryConfigStore store = new InMemoryConfigStore();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private String lastStoreKey;

    private int run(Map<String, String> env, String... args) {
        buffer.reset();
        L1ConfigCli cli = new L1ConfigCli(new PrintStream(buffer, true, StandardCharsets.UTF_8), env::get, key -> {
            lastStoreKey = key;
            return store;
        });
        return cli.run(args);
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void validateOfflineByDefault() {
        assertThat(run(Map.of(), "validate", "--dir", ConfigDirs.SOURCE.toString())).isZero();
        assertThat(output()).contains("offline").contains("golden: 8/8 passed").contains("OK: nothing written");
    }

    @Test
    void validateFailsWithExitCode1(@TempDir Path dir) {
        ConfigDirs.copy(dir);
        ConfigDirs.edit(dir.resolve("recipes/axis/axis.precheck.dedupe.json"), o -> o.put("lender", 5));
        assertThat(run(Map.of(), "validate", "--dir", dir.toString())).isEqualTo(1);
        assertThat(output()).contains("$.lender").contains("FAILED");
    }

    @Test
    void publishNeedsARegistryAndAReason() {
        assertThat(run(Map.of("USER", "dev"), "publish", "--env", "qa", "--reason", "x")).isEqualTo(2);
        assertThat(output()).contains("L1_CONFIG_MONGO_URI");
        assertThat(run(Map.of(), "publish", "--env", "qa", "--mongo-uri", "mongodb://x")).isEqualTo(2);
        assertThat(output()).contains("--reason is required");
    }

    @Test
    void publishThenStatusThenRedirect() {
        Map<String, String> env = Map.of("L1_CONFIG_MONGO_URI", "mongodb://registry", "GITHUB_ACTOR", "ci-bot");
        assertThat(run(env, "publish", "--env", "qa", "--reason", "axis dedupe", "--dir",
                ConfigDirs.SOURCE.toString())).isZero();
        assertThat(output()).contains("NEW       RECIPE    axis.precheck.dedupe@1").contains("OK: published");
        assertThat(lastStoreKey).isEqualTo("mongodb://registry|lih_config");
        assertThat(store.latest(DocType.RECIPE, "axis.precheck.dedupe")).hasValue(1);
        assertThat(store.publishLog()).singleElement().satisfies(l -> assertThat(l.get("by")).isEqualTo("ci-bot"));

        assertThat(run(env, "status")).isZero();
        assertThat(output()).contains("axis.precheck.dedupe@1").contains("lendercore-mongo");

        assertThat(run(env, "redirect", "--ref", "axis.precheck.dedupe", "--from", "1", "--to", "9",
                "--reason", "x")).isEqualTo(1);
        assertThat(output()).contains("axis.precheck.dedupe@9 is not published");
    }

    @Test
    void usageErrors() {
        assertThat(run(Map.of())).isEqualTo(2);
        assertThat(run(Map.of(), "frobnicate")).isEqualTo(2);
        assertThat(run(Map.of(), "validate", "--env")).isEqualTo(2);
        assertThat(output()).contains("usage:");
    }
}
