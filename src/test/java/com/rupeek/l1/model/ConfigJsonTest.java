package com.rupeek.l1.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigJsonTest {

    private static ByteArrayInputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unknownFieldsAreRejectedSoTyposFailEarly() {
        assertThatThrownBy(() -> ConfigJson.recipe(json("{\"recipe\": \"x\", \"stepz\": []}"), "x.json"))
                .isInstanceOf(ConfigJson.ConfigException.class)
                .hasMessageContaining("x.json")
                .hasMessageContaining("stepz");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> ConfigJson.recipe(json("{\"recipe\": \"x\", \"recipe\": \"y\"}"), "x.json"))
                .isInstanceOf(ConfigJson.ConfigException.class);
    }

    @Test
    void stepReturnAndDefaultsAreParsed() {
        Recipe r = ConfigJson.recipe(json("""
                {"recipe": "a.b.c", "steps": [
                  {"id": "s1", "op": "x.y@2", "in": {"k": "input.k"}, "onError": {"BUSINESS": "return:REJECTED"}},
                  {"id": "s2", "return": {"outcome": "DONE", "out": {"v": "1"}}}
                ], "return": {"outcome": "OK"}}"""), "a.json");
        assertThat(r.steps()).hasSize(2);
        assertThat(r.steps().get(0).isReturn()).isFalse();
        assertThat(r.steps().get(1).isReturn()).isTrue();
        assertThat(r.returnSpec().outcome()).isEqualTo("OK");
        assertThat(r.returnSpec().out()).isEmpty();
        assertThat(r.inputs()).isEmpty();
    }

    @Test
    void paramTypesAreLowerCaseInConfig() {
        OperationDef op = ConfigJson.operation(json("""
                {"op": "o", "kind": "MAP", "params": {"a": {"type": "string", "pii": true}, "b": {}}, "template": 1}"""),
                "o.json");
        assertThat(op.params().get("a")).isEqualTo(new ParamSpec(ParamType.STRING, true));
        assertThat(op.params().get("b").type()).isEqualTo(ParamType.DYN);
    }

    @Test
    void opRefParsing() {
        assertThat(OpRef.parse("axis.precheck.invoke@1")).isEqualTo(new OpRef("axis.precheck.invoke", 1));
        assertThat(OpRef.parse("a@b@3")).isEqualTo(new OpRef("a@b", 3));
        assertThatThrownBy(() -> OpRef.parse("axis.precheck.invoke")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpRef.parse("x@0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpRef.parse("x@v1")).isInstanceOf(IllegalArgumentException.class);
    }
}
