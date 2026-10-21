package com.rupeek.l1.publish;

import java.util.List;
import java.util.Map;

/**
 * One golden test: a recipe run with mocked operations and the expected result. Shape documented in
 * {@code l1-schemas/golden.schema.json}.
 *
 * @param recipe {@code ref} or {@code ref@version}
 * @param mocks  {@code op@version} to exactly one of {@code resp}, {@code result}, {@code transport}
 */
public record GoldenCase(
        String name,
        String recipe,
        Map<String, Object> input,
        Integer runNo,
        String now,
        Map<String, Map<String, Object>> mocks,
        Expect expect) {

    public GoldenCase {
        input = input == null ? Map.of() : input;
        mocks = mocks == null ? Map.of() : mocks;
    }

    /**
     * @param calls     {@code op@version} to {@code path -> expected value}, checked on the first call; paths start
     *                  at {@code params}, e.g. {@code params.body.first_name} or {@code params.body.address[0].address_city}
     * @param notCalled operations that must not run
     */
    public record Expect(
            String outcome,
            String failure,
            Map<String, Object> out,
            Map<String, Map<String, Object>> calls,
            List<String> notCalled) {

        public Expect {
            out = out == null ? Map.of() : out;
            calls = calls == null ? Map.of() : calls;
            notCalled = notCalled == null ? List.of() : notCalled;
        }
    }
}
