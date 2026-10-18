package com.rupeek.l1.publish;

import com.rupeek.l1.registry.InMemoryConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The golden runner must fail a case for every kind of wrong expectation, or golden tests prove nothing. */
class GoldenRunnerTest {

    private static final String DIR = "tests/axis/axis.precheck.dedupe/";

    @TempDir
    Path dir;

    @BeforeEach
    void copyConfig() {
        ConfigDirs.copy(dir);
    }

    private List<String> problemsOf(String file) {
        PublishReport r = new Publisher(new InMemoryConfigStore(), true, Clock.systemUTC())
                .validate(ConfigBundle.load(dir, new SchemaValidator()), "local");
        assertThat(r.errors()).isEmpty();
        return r.goldens().stream().filter(g -> g.source().endsWith(file)).findFirst().orElseThrow().problems();
    }

    @Test
    void theCheckedInCasesPass() {
        assertThat(problemsOf("01-fresh-accepted.golden.json")).isEmpty();
    }

    @Test
    void wrongCallValue() {
        ConfigDirs.edit(dir.resolve(DIR + "01-fresh-accepted.golden.json"), o -> o.with("expect").with("calls")
                .with("axis.precheck.invoke@1").put("params.body.first_name", "SURESH"));
        assertThat(problemsOf("01-fresh-accepted.golden.json"))
                .containsExactly("axis.precheck.invoke@1 params.body.first_name: expected SURESH but got RAVI");
    }

    @Test
    void wrongOutput() {
        ConfigDirs.edit(dir.resolve(DIR + "01-fresh-accepted.golden.json"),
                o -> o.with("expect").with("out").put("applicantId", "OTHER"));
        assertThat(problemsOf("01-fresh-accepted.golden.json")).singleElement().asString().startsWith("out.applicantId");
    }

    @Test
    void unexpectedCall() {
        ConfigDirs.edit(dir.resolve(DIR + "01-fresh-accepted.golden.json"),
                o -> o.with("expect").withArray("notCalled").add("axis.precheck.invoke@1"));
        assertThat(problemsOf("01-fresh-accepted.golden.json")).containsExactly("expected no call to axis.precheck.invoke@1");
    }

    @Test
    void wrongFailureType() {
        ConfigDirs.edit(dir.resolve(DIR + "06-axis-500-unknown.golden.json"),
                o -> o.with("expect").put("failure", "L1_TECHNICAL"));
        assertThat(problemsOf("06-axis-500-unknown.golden.json"))
                .containsExactly("expected failure L1_TECHNICAL but failed with L1_UNKNOWN");
    }

    @Test
    void missingHttpMock() {
        ConfigDirs.edit(dir.resolve(DIR + "01-fresh-accepted.golden.json"),
                o -> o.with("mocks").remove("axis.precheck.invoke@1"));
        assertThat(problemsOf("01-fresh-accepted.golden.json")).contains("no mock for HTTP operation axis.precheck.invoke@1");
    }

    @Test
    void unexpectedFailure() {
        ConfigDirs.edit(dir.resolve(DIR + "01-fresh-accepted.golden.json"), o -> o.with("mocks")
                .with("axis.precheck.invoke@1").with("resp").put("status", 503));
        assertThat(problemsOf("01-fresh-accepted.golden.json")).anyMatch(p -> p.startsWith("failed with L1_TECHNICAL"));
    }

    @Test
    void paths() {
        Map<String, Object> root = Map.of("params", Map.of("a", List.of(Map.of("b", 1))));
        assertThat(GoldenRunner.at(root, "params.a[0].b")).isEqualTo(1);
        assertThat(GoldenRunner.at(root, "params.a[5].b")).isNull();
        assertThat(GoldenRunner.at(root, "params.zz.b")).isNull();
        assertThatThrownBy(() -> GoldenRunner.at(root, "params.a.b")).isInstanceOf(IllegalArgumentException.class);
    }
}
