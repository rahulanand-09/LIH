package com.rupeek.lih;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Code-side request builders for Axis precheck, ported from AxisApiService.populateAxisPrecheckRequest.
 * Kept in code on purpose: name splitting and address building are logic, not field mapping.
 */
@Component
public class AxisTransformers {
    private final LihProperties props;

    public AxisTransformers(LihProperties props) { this.props = props; }

    public Map<String, Function<Map<String, Object>, Map<String, Object>>> all() {
        return Map.of("axisPrecheckRequest", this::precheckRequest, "axisStatusRequest", this::statusRequest);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> precheckRequest(Map<String, Object> vars) {
        Map<String, Object> c = (Map<String, Object>) vars.get("customer");
        Map<String, Object> r = base(vars);
        String proofName = str(c.get("customerProofName"));
        if (proofName != null && !proofName.isEmpty()) {
            String[] p = proofName.split(" ");
            if (p.length == 3) {
                r.put("first_name", up(p[0])); r.put("middle_name", up(p[1])); r.put("last_name", up(p[2]));
            } else if (p.length == 2) {
                r.put("first_name", up(p[0])); r.put("last_name", up(p[1]));
            } else if (p.length == 1) {
                r.put("first_name", up(p[0])); r.put("last_name", ".");
            } else {
                r.put("first_name", up(p[0])); r.put("middle_name", up(p[1]));
                r.put("last_name", up(String.join(" ", Arrays.copyOfRange(p, 2, p.length))));
            }
        } else {
            r.put("first_name", c.get("firstName"));
            r.put("last_name", c.get("lastName"));
        }
        r.put("date_of_birth", c.get("dob"));
        r.put("gender", c.get("gender"));
        r.put("email", c.get("email"));
        r.put("date_of_birth_as_per_pan", c.get("dob"));
        r.put("dedupe_type", "single");
        r.put("mobile_number", c.get("customerPhone"));
        r.put("name_as_per_pan", c.get("customerPanName"));
        r.put("dedupe_ids", List.of(dedupeId("PAN", c.get("pan")), dedupeId("AADHAR", c.get("aadhar"))));
        String formatted = str(c.get("customerAddress")).replaceAll("\\s+", " ").trim();
        r.put("address", new ArrayList<>(List.of(
                address("PERMANENT", formatted, (Map<String, Object>) c.get("permanentAddress")),
                address("COMMUNICATION", formatted, (Map<String, Object>) c.get("presentAddress")))));
        r.put("aadhar_number", c.get("aadhar"));
        return r;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> statusRequest(Map<String, Object> vars) {
        Map<String, Object> r = base(vars);
        Map<String, Object> dedupe = (Map<String, Object>) vars.get("dedupe");
        r.put("application_id", dedupe == null ? null : dedupe.get("applicantId"));
        return r;
    }

    /** Every key of AxisPrecheckRequest, nulls included, so the wire shape matches lender-core's DTO. */
    private Map<String, Object> base(Map<String, Object> vars) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("agreement_id", props.getAxisAgreementId());
        r.put("customer_id", vars.get("customerId"));
        r.put("application_id", null);
        r.put("client_loan_id", vars.get("clientLoanId"));
        r.put("model_name", "CLM1");
        r.put("customer_type", "individual");
        for (String k : List.of("first_name", "middle_name", "last_name", "name_as_per_pan", "date_of_birth",
                "date_of_birth_as_per_pan", "mobile_number", "email", "gender", "dedupe_type", "aadhar_number",
                "dedupe_ids", "address")) {
            r.put(k, null);
        }
        return r;
    }

    private static Map<String, Object> dedupeId(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id_type", type);
        m.put("id_value", value);
        return m;
    }

    private static Map<String, Object> address(String type, String value, Map<String, Object> a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("address_type", type);
        m.put("address_value", value);
        m.put("address_city", a.get("city"));
        m.put("address_state", a.get("state"));
        m.put("address_pincode", a.get("pin"));
        return m;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String up(String s) { return s.toUpperCase(); }
}
