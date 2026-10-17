package com.rupeek.l1.intercept;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    @Test
    void globalKeysAreMaskedAtAnyDepthAndTheInputIsUntouched() {
        Map<String, Object> customer = new HashMap<>();
        customer.put("pan", "ABCDE1234F");
        customer.put("Email", "ravi.test@example.com");
        customer.put("name", "RAVI");
        Map<String, Object> payload = Map.of("customer", customer, "count", 2);

        Object masked = masker.maskPayload(payload, List.of());

        assertThat(masked).isEqualTo(Map.of("customer",
                Map.of("pan", "XXXXXX234F", "Email", "XXXXXXXXXXXXXXXXX.com", "name", "RAVI"), "count", 2));
        assertThat(customer.get("pan")).isEqualTo("ABCDE1234F");
    }

    @Test
    void piiPathsMaskListsAndNestedFields() {
        Map<String, Object> payload = Map.of(
                "first_name", "RAVI",
                "dedupe_ids", List.of(Map.of("id_type", "PAN", "value", "ABCDE1234F"),
                        Map.of("id_type", "AADHAR", "value", "000011112222")),
                "address", List.of(Map.of("line", "12 MG Road", "city", "Bengaluru")));

        Object masked = masker.maskPayload(payload, List.of("$.dedupe_ids[*].value", "$.address[0].line", "$.first_name"));

        assertThat(masked).isEqualTo(Map.of(
                "first_name", "XXXX",                       // 4 chars or fewer: fully masked
                "dedupe_ids", List.of(Map.of("id_type", "PAN", "value", "XXXXXX234F"),
                        Map.of("id_type", "AADHAR", "value", "XXXXXXXX2222")),
                "address", List.of(Map.of("line", "XXXXXXRoad", "city", "Bengaluru"))));
    }

    @Test
    void pathsThatDoNotExistAreIgnored() {
        assertThat(masker.maskPayload(Map.of("a", 1), List.of("$.b.c", "$.a[*].x"))).isEqualTo(Map.of("a", 1));
        assertThat(masker.maskPayload(null, List.of("$.a"))).isNull();
        assertThat(masker.maskPayload("plain text", List.of("$.a"))).isEqualTo("plain text");
    }

    @Test
    void sensitiveValuesAreMaskedInFreeText() {
        String text = masker.maskText("lender rejected PAN ABCDE1234F for RAVI", List.of("ABCDE1234F", "RAVI"));
        assertThat(text).isEqualTo("lender rejected PAN XXXXXX234F for RAVI");   // short values (< 6) are left alone
    }
}
