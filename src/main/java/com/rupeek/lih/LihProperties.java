package com.rupeek.lih;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties("lih")
public class LihProperties {
    private String temporalTarget = "localhost:7233";
    private String namespace = "default";
    /** Lenders this instance runs workers for; one task queue "lender-{slug}" each. */
    private List<String> lenders = List.of("axis");
    private String axisAgreementId;
    private Core core = new Core();
    private RupeekWeb rupeekweb = new RupeekWeb();

    @Data
    public static class Core {
        private String url;
        private String customerDataPath; // ends with '?' like lender-core's getCustomerDataUrl
        private String token;
    }

    @Data
    public static class RupeekWeb {
        private String endpoint;
        private String precheckVerification;
        private String updatePrecheckFailure;
        private String adminToken;
    }
}
