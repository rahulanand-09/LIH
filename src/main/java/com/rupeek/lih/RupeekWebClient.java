package com.rupeek.lih;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.lender.entity.PrecheckEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/** Same two Rupeekweb calls as RupeekWebServiceProvider; like there, failures are logged, never thrown. */
@Slf4j
@Component
public class RupeekWebClient {
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper json;
    private final LihProperties.RupeekWeb cfg;

    public RupeekWebClient(LihProperties props, ObjectMapper json) {
        this.cfg = props.getRupeekweb();
        this.json = json;
    }

    public void updatePrecheckVerificationStatus(String loanRequestId, boolean approved) {
        try {
            HttpHeaders h = new HttpHeaders();
            h.add("Authorization", cfg.getAdminToken());
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("loanrequestId", loanRequestId);
            form.add("approved", String.valueOf(approved));
            http.exchange(cfg.getEndpoint() + cfg.getPrecheckVerification(), HttpMethod.POST, new HttpEntity<>(form, h), Object.class);
        } catch (Exception e) {
            log.error("Rupeekweb precheck verification failed for {}: {}", loanRequestId, e.getMessage());
        }
    }

    public void updatePrecheckFailureResponse(String loanRequestId, String lenderSlug, PrecheckEntity entity) {
        try {
            HttpHeaders h = new HttpHeaders();
            h.add("Authorization", cfg.getAdminToken());
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = new HashMap<>();
            body.put("loanRequestId", loanRequestId);
            body.put("lenderSlug", lenderSlug);
            body.put("lendercustomerprofile", entity != null ? json.writeValueAsString(entity) : "{}");
            http.exchange(cfg.getEndpoint() + cfg.getUpdatePrecheckFailure(), HttpMethod.POST, new HttpEntity<>(body, h), Object.class);
        } catch (Exception e) {
            log.error("Rupeekweb precheck failure update failed for {}: {}", loanRequestId, e.getMessage());
        }
    }
}
