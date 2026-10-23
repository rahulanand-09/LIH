package com.rupeek.flow.observability;

import ch.qos.logback.classic.Level;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.expr.CelExpressionEngine;
import com.rupeek.flow.expr.ExprLimits;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.resource.AuthProviders;
import com.rupeek.flow.resource.ClasspathResourceProvider;
import com.rupeek.flow.resource.EnvSecretResolver;
import com.rupeek.flow.resource.ResourceConfigLoader;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.executor.http.HttpClientFactory;
import com.rupeek.flow.task.executor.http.HttpTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Proves an HTTP task with an auth header and PII in the body/query never leaks them into logs. */
class HttpLogSafetyTest {

    private static final String API_KEY = "sk-live-SUPERSECRET-0123456789";
    private static final String PAN = "ABCDE1234F";
    private static final String ACCOUNT = "123456789012";
    private static final String RESP_PAN = "ZZZZZ9999Z";

    private WireMockServer wm;
    private HttpTaskExecutor http;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(post(urlPathEqualTo("/precheck")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"OK\",\"pan\":\"" + RESP_PAN + "\"}")));
        Map<String, String> env = Map.of("AXIS_PRECHECK_BASEURL", wm.baseUrl(), "AXIS_PRECHECK_API_KEY", API_KEY,
                "AXIS_PRECHECK_AGREEMENT_ID", "AGR-1");
        ExpressionEngine expressions = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
        http = new HttpTaskExecutor(new TemplateEvaluator(expressions), expressions,
                new ClasspathResourceProvider(ResourceConfigLoader.load("classpath:flow-config/resources"), "local", env::get),
                new HttpClientFactory(), AuthProviders.defaults(), new EnvSecretResolver(env::get),
                new TaskAuditor(record -> { }, new PiiMasker(), Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private void callOnce() {
        Map<String, Object> fields = Map.of(
                "resource", "axis-precheck", "method", "POST", "path", "/precheck",
                "query", Map.of("pan", "=inputs.pan", "apiKey", "=inputs.pan", "customerRef", "=inputs.account"),
                "body", Map.of("pan", "=inputs.pan", "bankAccount", "=inputs.account", "note", "hello"),
                "piiPaths", List.of("$.bankAccount"),
                "success", "resp.status == 200");
        http.execute(new TaskRequest(OperationKind.HTTP, "t.ref", 1, "case-1", "n1", 1,
                Map.of("pan", PAN, "account", ACCOUNT), fields));
        // the secret really was sent, so its absence from the logs is meaningful
        wm.verify(postRequestedFor(urlPathEqualTo("/precheck")).withHeader("API-key", equalTo(API_KEY)));
    }

    @Test
    void infoLogsContainNoSecretAndNoPii() {
        try (LogCapture logs = new LogCapture(Level.INFO)) {
            callOnce();
            assertThat(logs.text()).contains("http call resource=axis-precheck", "status=200");
            assertThat(logs.text()).doesNotContain(API_KEY, PAN, ACCOUNT, RESP_PAN, "hello");
        }
    }

    @Test
    void debugLogsShowBodiesOnlyMasked() {
        try (LogCapture logs = new LogCapture(Level.DEBUG)) {
            callOnce();
            String out = logs.text();
            assertThat(out).contains("http request", "http response", "\"note\":\"hello\"");
            assertThat(out).contains("XXXXXX234F", "XXXXXXXX9012", "XXXXXX999Z"); // masked, last 4 kept
            assertThat(out).doesNotContain(API_KEY, PAN, ACCOUNT, RESP_PAN);
            assertThat(out).contains("apiKey=***");
        }
    }
}
