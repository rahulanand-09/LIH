package com.rupeek.lih;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local stand-ins for Axis, core and Rupeekweb so the whole flow runs on a laptop. Profile "mock" only. */
@Slf4j
@Profile("mock")
@RestController
public class MockLenders {
    private final Map<String, String> flags = new ConcurrentHashMap<>(
            Map.of("dedupe", "Y", "pan", "Y", "ramp", "Y", "hunter", "Y"));

    @PostMapping("/mock/axis/precheck")
    public Map<String, Object> axis(@RequestParam String type, @RequestBody Map<String, Object> body) {
        log.info("[mock-axis] type={} customer_id={} client_loan_id={}", type, body.get("customer_id"), body.get("client_loan_id"));
        switch (type) {
            case "dedupe": return Map.of("data", Map.of("applicantId", "APP-" + body.get("customer_id")));
            case "dedupe_timble": return Map.of("reference_id", "TIMBLE-" + body.get("customer_id"));
            case "dedupe_status": return new HashMap<>(flags);
            default: return Map.of("errors", List.of(Map.of("message", "unknown type " + type)));
        }
    }

    /** Flip a flag, e.g. POST /mock/axis/flags {"pan":"WIP"} */
    @PostMapping("/mock/axis/flags")
    public Map<String, String> setFlags(@RequestBody Map<String, String> f) {
        flags.putAll(f);
        return flags;
    }

    @GetMapping("/mock/core/customerdata")
    public Map<String, Object> core(@RequestParam String loanRequestIds) {
        Map<String, Object> addr = Map.of("city", "Bengaluru", "state", "Karnataka", "pin", "560001");
        Map<String, Object> customer = new HashMap<>();
        customer.put("id", "CUST-" + loanRequestIds);
        customer.put("customerProofName", "Asha Devi Kumar");
        customer.put("dob", "1990-05-17");
        customer.put("gender", "F");
        customer.put("email", "asha@example.com");
        customer.put("customerPhone", "9000000001");
        customer.put("customerPanName", "ASHA DEVI KUMAR");
        customer.put("pan", "ABCDE1234F");
        customer.put("aadhar", "999911112222");
        customer.put("customerAddress", "12  MG Road,   Bengaluru");
        customer.put("permanentAddress", addr);
        customer.put("presentAddress", addr);
        return Map.of("loanData", List.of(Map.of("customer", customer)));
    }

    @PostMapping("/mock/rupeekweb/precheckverification")
    public String verified(@RequestBody(required = false) String body) {
        log.info("[mock-rupeekweb] precheck verification: {}", body);
        return "ok";
    }

    @PostMapping("/mock/rupeekweb/precheckfailure")
    public String failed(@RequestBody(required = false) String body) {
        log.info("[mock-rupeekweb] precheck failure: {}", body);
        return "ok";
    }
}
