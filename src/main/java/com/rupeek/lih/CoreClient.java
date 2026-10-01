package com.rupeek.lih;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fetches loanData[0].customer from core, same URL shape as lender-core's Core.getCoreCustomerData. */
@Slf4j
@Component
public class CoreClient {
    private final RestTemplate http = new RestTemplate();
    private final LihProperties.Core cfg;

    public CoreClient(LihProperties props) { this.cfg = props.getCore(); }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> customerByLoanRequest(String loanRequestId) {
        String url = cfg.getUrl() + cfg.getCustomerDataPath()
                + "loanRequestIds=" + loanRequestId + "&loanIds=&lpLoanIds=&uLoanIds=&uLpLoanIds=";
        try {
            HttpHeaders h = new HttpHeaders();
            h.add("Accept", MediaType.APPLICATION_JSON_VALUE);
            h.add("Authorization", cfg.getToken());
            Map<String, Object> body = http.exchange(url, HttpMethod.GET, new HttpEntity<>(null, h),
                    new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
            List<Map<String, Object>> loanData = body == null ? null : (List<Map<String, Object>>) body.get("loanData");
            if (loanData == null || loanData.isEmpty()) return Optional.empty();
            return Optional.ofNullable((Map<String, Object>) loanData.get(0).get("customer"));
        } catch (Exception e) {
            log.error("Core customer fetch failed for {}: {}", loanRequestId, e.getMessage());
            return Optional.empty();
        }
    }
}
