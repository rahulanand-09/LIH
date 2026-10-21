package com.rupeek.l1.ops;

import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.ops.gen.GenOperationExecutor;
import com.rupeek.l1.ops.map.MapOperationExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.rupeek.l1.ops.OpsTestSupport.TEMPLATES;
import static com.rupeek.l1.ops.OpsTestSupport.op;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapAndGenExecutorTest {

    private final MapOperationExecutor map = new MapOperationExecutor(TEMPLATES);
    private final GenOperationExecutor gen = new GenOperationExecutor();
    private final ExecContext ctx = new ExecContext(null, null, Map.of("agreementId", "2181"),
            Map.of("now", Instant.parse("2026-10-17T10:00:00Z")), new CallRecorder());

    @Test
    void mapRendersItsTemplate() {
        OperationDef def = op("""
                {"op": "m", "kind": "MAP", "params": {"name": {}},
                 "template": {"agreement_id": "=env.agreementId", "first": "=params.name.upperAscii()", "fixed": "CLM1"}}""");
        Object result = map.execute(map.prepare(def), Map.of("name", "ravi"), ctx).get("result");
        assertThat(result).isEqualTo(Map.of("agreement_id", "2181", "first", "RAVI", "fixed", "CLM1"));
    }

    @Test
    void mapNeedsATemplate() {
        assertThatThrownBy(() -> map.prepare(op("{\"op\": \"m\", \"kind\": \"MAP\"}"))).isInstanceOf(ConfigException.class);
    }

    @Test
    void stableIdReusesOrDerivesTheSameIdEveryTime() {
        PreparedOperation p = gen.prepare(op("""
                {"op": "gen.stableId", "kind": "GEN", "action": "STABLE_ID", "params": {"reuse": {}, "seed": {}}}"""));
        Map<String, Object> fresh = new HashMap<>();
        fresh.put("reuse", "");
        fresh.put("seed", "LR-1:r1:axis-dedupe");

        Object first = gen.execute(p, fresh, ctx).get("result");
        Object again = gen.execute(p, fresh, ctx).get("result");
        Object reused = gen.execute(p, Map.of("reuse", "CL-OLD", "seed", "LR-1:r1:axis-dedupe"), ctx).get("result");

        assertThat(first).isEqualTo("d006f1c4-4815-56eb-abf8-dc0df9be7ea3").isEqualTo(again);
        assertThat(reused).isEqualTo("CL-OLD");
    }

    @Test
    void stableIdWithoutSeedFails() {
        PreparedOperation p = gen.prepare(op("""
                {"op": "g", "kind": "GEN", "action": "STABLE_ID", "params": {"seed": {}}}"""));
        assertThatThrownBy(() -> gen.execute(p, Map.of(), ctx)).isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> gen.prepare(op("{\"op\": \"g\", \"kind\": \"GEN\", \"action\": \"STABLE_ID\"}")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> gen.prepare(op("{\"op\": \"g\", \"kind\": \"GEN\", \"action\": \"RANDOM\"}")))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void nowReturnsTheAttemptTimestamp() {
        PreparedOperation p = gen.prepare(op("{\"op\": \"g\", \"kind\": \"GEN\", \"action\": \"NOW\"}"));
        assertThat(gen.execute(p, Map.of(), ctx).get("result")).isEqualTo("2026-10-17T10:00:00Z");
    }
}
