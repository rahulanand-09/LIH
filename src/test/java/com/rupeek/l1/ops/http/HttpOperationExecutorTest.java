package com.rupeek.l1.ops.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;
import com.rupeek.l1.secrets.EnvSecretResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.rupeek.l1.ops.OpsTestSupport.TEMPLATES;
import static com.rupeek.l1.ops.OpsTestSupport.op;
import static com.rupeek.l1.ops.OpsTestSupport.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOperationExecutorTest {

    private static WireMockServer server;

    private final Map<String, String> secretStore = new HashMap<>(Map.of("TEST_API_KEY", "s3cr3t-key"));
    private final HttpOperationExecutor executor = new HttpOperationExecutor(TEMPLATES, new HttpClientFactory(),
            AuthProviders.defaults(), new EnvSecretResolver(secretStore::get));

    private static final Resource RESOURCE = resource("""
            {"resource": "lender-x", "kind": "HTTP",
             "auth": {"type": "API_KEY_HEADER", "header": "API-key", "secretRef": "TEST_API_KEY"},
             "headers": {"X-Static": "yes"},
             "timeoutMs": {"connect": 2000, "read": 2000},
             "env": {"local": {"baseUrl": "http://unused", "values": {"agreementId": "2181"}}}}""");

    @BeforeAll
    static void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private ExecContext ctx(CallRecorder recorder) {
        return new ExecContext(RESOURCE, server.baseUrl(), Map.of("agreementId", "2181"), Map.of(), recorder);
    }

    private Map<String, Object> run(OperationDef def, Map<String, Object> params, CallRecorder recorder) throws TransportException {
        PreparedOperation prepared = executor.prepare(def);
        return executor.execute(prepared, params, ctx(recorder));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resp(Map<String, Object> scope) {
        return (Map<String, Object>) scope.get("resp");
    }

    // ------------------------------------------------------------------ requests

    @Test
    void getBuildsQueryAndHeadersAndAddsAuthWithoutRecordingIt() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/customers"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"id\": \"C1\"}")));
        OperationDef def = op("""
                {"op": "x.get", "kind": "HTTP", "resource": "lender-x", "params": {"lr": {"type": "string"}},
                 "request": {"method": "GET", "path": "/api/customers",
                             "query": {"loanRequestIds": "=params.lr", "loanIds": "", "skip": null},
                             "headers": {"X-Agreement": "=env.agreementId"}},
                 "success": "resp.status == 200"}""");
        CallRecorder recorder = new CallRecorder();

        Map<String, Object> resp = resp(run(def, Map.of("lr", "LR 1&2"), recorder));

        assertThat(resp.get("status")).isEqualTo(200);
        assertThat(resp.get("body")).isEqualTo(Map.of("id", "C1"));
        server.verify(getRequestedFor(urlPathEqualTo("/api/customers"))
                .withQueryParam("loanRequestIds", equalTo("LR 1&2"))
                .withQueryParam("loanIds", equalTo(""))
                .withQueryParam("skip", absent())
                .withHeader("API-key", equalTo("s3cr3t-key"))
                .withHeader("X-Static", equalTo("yes"))
                .withHeader("X-Agreement", equalTo("2181")));
        assertThat(recorder.method()).isEqualTo("GET");
        assertThat(recorder.target()).startsWith(server.baseUrl() + "/api/customers?");
        assertThat(recorder.requestHeaders()).containsKeys("X-Static", "X-Agreement").doesNotContainKey("API-key");
        assertThat(recorder.responseStatus()).isEqualTo(200);
    }

    @Test
    void postSendsTheRenderedJsonBody() throws Exception {
        server.stubFor(post(urlPathEqualTo("/invoke")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"data\": {\"applicantId\": \"A9\"}}")));
        OperationDef def = op("""
                {"op": "x.post", "kind": "HTTP", "resource": "lender-x", "params": {"type": {}, "body": {}},
                 "request": {"method": "POST", "path": "/invoke", "query": {"type": "=params.type"}, "body": "=params.body"},
                 "success": "resp.status == 200"}""");

        Map<String, Object> resp = resp(run(def, Map.of("type", "dedupe", "body", Map.of("first_name", "RAVI", "n", 2)),
                new CallRecorder()));

        assertThat(resp.get("body")).isEqualTo(Map.of("data", Map.of("applicantId", "A9")));
        server.verify(postRequestedFor(urlPathEqualTo("/invoke"))
                .withQueryParam("type", equalTo("dedupe"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"first_name\": \"RAVI\", \"n\": 2}")));
    }

    @Test
    void formEncodingSendsUrlEncodedFields() throws Exception {
        server.stubFor(post(urlPathEqualTo("/verify")).willReturn(aResponse().withStatus(204)));
        OperationDef def = op("""
                {"op": "x.form", "kind": "HTTP", "resource": "lender-x", "params": {"lr": {}},
                 "request": {"method": "POST", "path": "/verify", "bodyEncoding": "FORM",
                             "body": {"loanrequestId": "=params.lr", "approved": "true"}},
                 "success": "resp.status == 204"}""");

        Map<String, Object> resp = resp(run(def, Map.of("lr", "LR-1"), new CallRecorder()));

        assertThat(resp.get("status")).isEqualTo(204);
        assertThat(resp.get("body")).isNull();
        server.verify(postRequestedFor(urlPathEqualTo("/verify"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("loanrequestId=LR-1&approved=true")));
    }

    // ------------------------------------------------------------------ responses are data

    @Test
    void errorStatusesAndRedirectsAreReturnedNotThrown() throws Exception {
        server.stubFor(get(urlPathEqualTo("/fail")).willReturn(aResponse().withStatus(500).withBody("oops")));
        server.stubFor(get(urlPathEqualTo("/moved")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://elsewhere/")));
        OperationDef fail = op("""
                {"op": "x.fail", "kind": "HTTP", "resource": "lender-x",
                 "request": {"method": "GET", "path": "/fail"}, "success": "resp.status == 200"}""");
        OperationDef moved = op("""
                {"op": "x.moved", "kind": "HTTP", "resource": "lender-x",
                 "request": {"method": "GET", "path": "/moved"}, "success": "resp.status == 200"}""");

        Map<String, Object> failed = resp(run(fail, Map.of(), new CallRecorder()));
        assertThat(failed.get("status")).isEqualTo(500);
        assertThat(failed.get("body")).isEqualTo("oops");
        assertThat(resp(run(moved, Map.of(), new CallRecorder())).get("status")).isEqualTo(302);
    }

    @Test
    void invalidJsonWithAJsonContentTypeIsPermanent() {
        server.stubFor(get(urlPathEqualTo("/bad")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{not json")));
        OperationDef def = op("""
                {"op": "x.bad", "kind": "HTTP", "resource": "lender-x",
                 "request": {"method": "GET", "path": "/bad"}, "success": "true"}""");

        assertThatThrownBy(() -> run(def, Map.of(), new CallRecorder()))
                .isInstanceOf(OperationException.class)
                .satisfies(e -> assertThat(((OperationException) e).errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    // ------------------------------------------------------------------ transport failures

    @Test
    void readTimeoutMeansSentAndTimedOut() {
        server.stubFor(post(urlPathEqualTo("/slow")).willReturn(aResponse().withFixedDelay(1500).withBody("{}")));
        OperationDef def = op("""
                {"op": "x.slow", "kind": "HTTP", "resource": "lender-x", "timeoutMs": 300,
                 "request": {"method": "POST", "path": "/slow", "body": {"a": 1}}, "success": "true"}""");

        assertThatThrownBy(() -> run(def, Map.of(), new CallRecorder()))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> {
                    TransportException t = (TransportException) e;
                    assertThat(t.sent()).isTrue();
                    assertThat(t.timeout()).isTrue();
                });
    }

    @Test
    void refusedConnectionMeansNotSent() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OperationDef def = op("""
                {"op": "x.down", "kind": "HTTP", "resource": "lender-x",
                 "request": {"method": "POST", "path": "/x", "body": {"a": 1}}, "success": "true"}""");
        PreparedOperation prepared = executor.prepare(def);
        ExecContext down = new ExecContext(RESOURCE, "http://localhost:" + closedPort, Map.of(), Map.of(), new CallRecorder());

        assertThatThrownBy(() -> executor.execute(prepared, Map.of(), down))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(((TransportException) e).sent()).isFalse());
    }

    // ------------------------------------------------------------------ safety and config errors

    @Test
    void literalPathsWithTraversalOrHostAreRejectedAtPrepare() {
        for (String path : List.of("/a/../b", "http://evil.example/x", "//evil.example/x")) {
            OperationDef def = op("""
                    {"op": "x.p", "kind": "HTTP", "resource": "lender-x",
                     "request": {"method": "GET", "path": "%s"}, "success": "true"}""".formatted(path));
            assertThatThrownBy(() -> executor.prepare(def)).as(path).isInstanceOf(ConfigException.class);
        }
    }

    @Test
    void renderedPathsWithTraversalAreRejectedAtRuntime() {
        OperationDef def = op("""
                {"op": "x.p", "kind": "HTTP", "resource": "lender-x", "params": {"p": {}},
                 "request": {"method": "GET", "path": "=params.p"}, "success": "true"}""");
        for (String path : List.of("../admin", "/ok/../../admin", "https://evil.example/")) {
            assertThatThrownBy(() -> run(def, Map.of("p", path), new CallRecorder())).as(path)
                    .isInstanceOf(OperationException.class);
        }
        server.verify(0, getRequestedFor(urlPathEqualTo("/admin")));
    }

    @Test
    void missingSecretIsPermanent() {
        secretStore.clear();
        server.stubFor(get(urlPathEqualTo("/x")).willReturn(aResponse().withBody("{}")));
        OperationDef def = op("""
                {"op": "x.s", "kind": "HTTP", "resource": "lender-x",
                 "request": {"method": "GET", "path": "/x"}, "success": "true"}""");

        assertThatThrownBy(() -> run(def, Map.of(), new CallRecorder()))
                .isInstanceOf(OperationException.class).hasMessageContaining("TEST_API_KEY");
        server.verify(0, getRequestedFor(urlPathEqualTo("/x")));
    }

    @Test
    void invalidDefinitionsFailAtPrepare() {
        assertThatThrownBy(() -> executor.prepare(op("""
                {"op": "x", "kind": "HTTP", "resource": "r", "request": {"method": "FETCH", "path": "/x"}, "success": "true"}""")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("method");
        assertThatThrownBy(() -> executor.prepare(op("""
                {"op": "x", "kind": "HTTP", "resource": "r", "request": {"method": "GET", "path": "/x"}}""")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("success");
        assertThatThrownBy(() -> executor.prepare(op("""
                {"op": "x", "kind": "HTTP", "request": {"method": "GET", "path": "/x"}, "success": "true"}""")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("resource");
    }
}
