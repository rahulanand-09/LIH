package com.rupeek.flow.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** logback-spring.xml: JSON outside local/dev with MDC fields, readable inside; plus the request-id filter. */
@ExtendWith(OutputCaptureExtension.class)
class LogFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The Spring app above re-initialises logback; put the suite's own test config back for later test classes. */
    @org.junit.jupiter.api.AfterEach
    void restoreTestLogging() throws Exception {
        var context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        var joran = new ch.qos.logback.classic.joran.JoranConfigurator();
        joran.setContext(context);
        joran.doConfigure(getClass().getResource("/logback-test.xml"));
    }

    private String logLineWithProfile(String profile, CapturedOutput output, String marker) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties("spring.profiles.active=" + profile, "logging.config=classpath:logback-spring.xml",
                        "spring.main.banner-mode=off")
                .run()) {
            Logger log = LoggerFactory.getLogger("com.rupeek.flow.probe");
            try (LogContext ignored = LogContext.with(LogContext.LOAN_REQUEST_ID, "LR-9", LogContext.WORKFLOW_ID, "wf-9")) {
                log.info(marker);
            }
        }
        return output.getAll().lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
    }

    @Test
    void prodProfileEmitsOneJsonObjectPerLineWithMdc(CapturedOutput output) throws Exception {
        String line = logLineWithProfile("prod", output, "json-marker");
        JsonNode node = JSON.readTree(line);
        assertThat(node.path("message").asText()).isEqualTo("json-marker");
        assertThat(node.path("loanRequestId").asText()).isEqualTo("LR-9");
        assertThat(node.path("workflowId").asText()).isEqualTo("wf-9");
    }

    @Test
    void localProfileIsHumanReadable(CapturedOutput output) {
        String line = logLineWithProfile("local", output, "plain-marker");
        assertThat(line).doesNotStartWith("{").contains("loanRequestId=LR-9", "plain-marker");
    }

    @Test
    void requestIdFilterGeneratesOrAcceptsAndEchoesTheId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        String[] seenInside = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInside[0] = org.slf4j.MDC.get(LogContext.REQUEST_ID);
            }
        };

        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(withHeader, res1, chain);
        assertThat(seenInside[0]).isEqualTo("abc-123");
        assertThat(res1.getHeader("X-Request-Id")).isEqualTo("abc-123");

        MockHttpServletRequest hostile = new MockHttpServletRequest();
        hostile.addHeader("X-Request-Id", "x\nFAKE LOG LINE");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(hostile, res2, chain);
        assertThat(seenInside[0]).matches("[0-9a-f-]{36}");
        assertThat(res2.getHeader("X-Request-Id")).isEqualTo(seenInside[0]);
        assertThat(org.slf4j.MDC.get(LogContext.REQUEST_ID)).isNull();
    }

    @org.springframework.context.annotation.Configuration
    static class Empty {
    }
}
