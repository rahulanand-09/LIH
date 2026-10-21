package com.rupeek.l1.expr.fn;

import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.ExprEvalException;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.ScopeDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RkFunctionsTest {

    private final ExpressionEngine engine = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));

    private Object cel(String source, Map<String, Object> input) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", input);
        return engine.eval(engine.compile(source, ScopeDecl.STEP, "test"), vars);
    }

    // ------------------------------------------------------------ round

    @ParameterizedTest
    @CsvSource({
            "2.345, 2, HALF_UP,   2.35",
            "2.345, 2, HALF_DOWN, 2.34",
            "1.25,  1, HALF_UP,   1.3",
            "1.25,  1, HALF_DOWN, 1.2",   // legacy roundOffDown is HALF_DOWN, not DOWN
            "1.29,  1, DOWN,      1.2",
    })
    void roundMatchesLegacyBigDecimalRounding(double value, long scale, String mode, double expected) {
        assertThat(RkFunctions.round(value, scale, mode)).isEqualTo(expected);
    }

    @Test
    void roundHasIntAndDoubleOverloads() {
        assertThat(cel("rk.round(2.345, 2, 'HALF_UP')", Map.of())).isEqualTo(2.35);
        assertThat(cel("rk.round(5, 2, 'HALF_UP')", Map.of())).isEqualTo(5.0);
    }

    // ------------------------------------------------------------ sum / group

    @Test
    void sumIsExactAndKeepsIntegers() {
        assertThat(cel("rk.sum([1, 2, 3])", Map.of())).isEqualTo(6L);
        assertThat(cel("rk.sum([0.1, 0.2])", Map.of())).isEqualTo(0.3);
        assertThat(cel("rk.sum(input.rows, 'amt')", Map.of("rows", List.of(Map.of("amt", 10), Map.of("amt", 2.5)))))
                .isEqualTo(12.5);
    }

    @Test
    void sumRejectsNonNumbers() {
        assertThatThrownBy(() -> cel("rk.sum(['a'])", Map.of())).isInstanceOf(ExprEvalException.class)
                .hasMessageContaining("rk.sum");
    }

    @Test
    void groupSumAndGroupCountKeepFirstSeenOrder() {
        List<Map<String, Object>> rows = List.of(
                Map.of("type", "RING", "n", 2), Map.of("type", "CHAIN", "n", 1), Map.of("type", "RING", "n", 3));
        assertThat(cel("rk.groupSum(input.rows, 'type', 'n')", Map.of("rows", rows)))
                .isEqualTo(Map.of("RING", 5L, "CHAIN", 1L));
        assertThat(cel("rk.groupCount(input.rows, 'type')", Map.of("rows", rows)))
                .isEqualTo(Map.of("RING", 2L, "CHAIN", 1L));
    }

    // ------------------------------------------------------------ lookup / json

    @Test
    void lookupReturnsValueOrDefault() {
        assertThat(cel("rk.lookup({'18': 18, '19': 18}, '19', 0)", Map.of())).isEqualTo(18L);
        assertThat(cel("rk.lookup({'18': 18}, '24', 24)", Map.of())).isEqualTo(24L);
        assertThat(cel("rk.lookup({'a': null}, 'a', 'dflt')", Map.of())).isNull();
    }

    @Test
    void jsonRoundTrips() {
        assertThat(cel("rk.json({'a': 1, 'b': [true, null]})", Map.of())).isEqualTo("{\"a\":1,\"b\":[true,null]}");
        assertThat(cel("rk.parseJson('{\"a\":1}').a", Map.of())).isEqualTo(1L);
    }

    // ------------------------------------------------------------ dates / mask

    @Test
    void dateFormatAndParse() {
        assertThat(cel("rk.date.format('1990-01-05', 'ISO', 'dd-MMM-yyyy').upperAscii()", Map.of()))
                .isEqualTo("05-JAN-1990");
        assertThat(cel("rk.date.parse('05/01/1990', 'dd/MM/yyyy')", Map.of())).isEqualTo("1990-01-05");
        assertThat(cel("rk.date.format('2026-10-17T10:15:30', 'ISO', 'dd-MM-yyyy HH:mm')", Map.of()))
                .isEqualTo("17-10-2026 10:15");
        assertThat(cel("rk.date.format(null, 'ISO', 'dd-MM-yyyy')", Map.of())).isNull();
    }

    @Test
    void dateWithWrongPatternFails() {
        assertThatThrownBy(() -> cel("rk.date.parse('1990/01/05', 'dd-MM-yyyy')", Map.of()))
                .isInstanceOf(ExprEvalException.class).hasMessageContaining("does not match");
    }

    @Test
    void maskKeepsTheTail() {
        assertThat(RkFunctions.mask("ABCDE1234F", 4)).isEqualTo("XXXXXX234F");
        assertThat(RkFunctions.mask("123", 4)).isEqualTo("XXX");
        assertThat(cel("rk.mask(null, 4)", Map.of())).isNull();
    }

    // ------------------------------------------------------------ nameSplit (legacy Axis rule)

    @ParameterizedTest
    @CsvSource(value = {
            "Ravi                     | RAVI | null  | .",
            "Ravi Kumar               | RAVI | null  | KUMAR",
            "Ravi Kumar Sharma        | RAVI | KUMAR | SHARMA",
            "Ravi Kumar Singh Sharma  | RAVI | KUMAR | SINGH SHARMA",
            "'Ravi  Kumar'            | RAVI | ''    | KUMAR",       // double space: legacy split quirk kept
    }, delimiter = '|', nullValues = "null")
    void nameSplitFollowsLegacyRule(String proofName, String first, String middle, String last) {
        Map<String, Object> customer = Map.of("customerProofName", proofName, "firstName", "x", "lastName", "y");
        assertThat(RkFunctions.nameSplit(customer, "first")).isEqualTo(first);
        assertThat(RkFunctions.nameSplit(customer, "middle")).isEqualTo(middle);
        assertThat(RkFunctions.nameSplit(customer, "last")).isEqualTo(last);
    }

    @Test
    void nameSplitFallsBackToRawFirstAndLastName() {
        Map<String, Object> customer = new HashMap<>();
        customer.put("customerProofName", "");
        customer.put("firstName", "Ravi");
        customer.put("lastName", "Kumar");
        assertThat(cel("rk.nameSplit(input.c, 'first')", Map.of("c", customer))).isEqualTo("Ravi");
        assertThat(cel("rk.nameSplit(input.c, 'middle')", Map.of("c", customer))).isNull();
        assertThat(cel("rk.nameSplit(input.c, 'last')", Map.of("c", customer))).isEqualTo("Kumar");
    }

    @Test
    void nameSplitRejectsUnknownPart() {
        assertThatThrownBy(() -> cel("rk.nameSplit({'customerProofName': 'A B'}, 'surname')", Map.of()))
                .isInstanceOf(ExprEvalException.class).hasMessageContaining("part must be");
    }

    // ------------------------------------------------------------ uuidFrom

    @Test
    void uuidFromIsVersion5AndMatchesAReferenceImplementation() {
        // Expected values from Python: uuid.uuid5(uuid.NAMESPACE_URL, seed)
        assertThat(RkFunctions.uuidFrom("LR-1:r1:axis-dedupe")).isEqualTo("d006f1c4-4815-56eb-abf8-dc0df9be7ea3");
        assertThat(RkFunctions.uuidFrom("hello")).isEqualTo("074171de-bc84-5ea4-b636-1135477620e1");
        UUID id = UUID.fromString(RkFunctions.uuidFrom("anything"));
        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2);
    }
}
