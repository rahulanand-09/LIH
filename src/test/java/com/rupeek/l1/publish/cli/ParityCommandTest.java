package com.rupeek.l1.publish.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.mongodb.client.MongoDatabase;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.parity.LegacyAxisPrecheckMapping;
import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.registry.InMemoryConfigStore;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code parity} end to end: audit rows in Mongo, customers from a (stub) Core, nothing written, Axis never called. */
class ParityCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private WireMockServer core;
    private MongoDatabase lendercore;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        core = new WireMockServer(options().dynamicPort());
        core.start();
        lendercore = MongoTestSupport.client().getDatabase("lc_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void tearDown() {
        core.stop();
    }

    private static Map<String, Object> customer(String id, String proofName) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("customerProofName", proofName);
        c.put("customerPhone", "9000000099");
        c.put("gender", "male");
        c.put("dob", "1990-01-01");
        c.put("customerAddress", "1 Test Road");
        c.put("permanentAddress", Map.of("city", "Pune", "state", "MH", "pin", "411001"));
        return c;
    }

    private void coreKnows(String loanRequestId, Map<String, Object> customer) throws Exception {
        core.stubFor(get(urlPathEqualTo("/api/support/fetchloandetailsv2"))
                .withQueryParam("loanRequestIds", equalTo(loanRequestId))
                .withHeader("Authorization", equalTo("core-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        JSON.writeValueAsString(Map.of("loandata", List.of(Map.of("customer", customer)))))));
    }

    private void legacyLogged(String loanRequestId, String payload) {
        lendercore.getCollection("LenderApiAuditLog").insertOne(new Document("lenderSlug", "axis")
                .append("loanRequestId", loanRequestId).append("requestType", "PRECHECK_DEDUPE")
                .append("requestPayload", payload).append("apiInvokedAt", new Date()));
    }

    private int parity(String... extra) {
        Map<String, String> env = Map.of("CORE_BASEURL", core.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", "http://axis.must-not-be-called.invalid");
        L1ConfigCli cli = new L1ConfigCli(new PrintStream(buffer, true, StandardCharsets.UTF_8), env::get,
                key -> new InMemoryConfigStore(), key -> {
                    assertThat(key).isEqualTo("mongodb://audit|" + lendercore.getName());
                    return lendercore;
                });
        List<String> args = new java.util.ArrayList<>(List.of("parity", "--dir", ConfigDirs.SOURCE.toString(),
                "--env", "local", "--recipe", "axis.precheck.dedupe", "--capture", "axis.precheck.invoke",
                "--live", "core.fetchCustomerByLoanRequest", "--request-type", "PRECHECK_DEDUPE", "--lender", "axis",
                "--ignore", "client_loan_id", "--audit-uri", "mongodb://audit", "--audit-db", lendercore.getName()));
        args.addAll(List.of(extra));
        return cli.run(args.toArray(String[]::new));
    }

    @Test
    void matchesDiffsAndErrorsAreReportedWithMaskedValues() throws Exception {
        Map<String, Object> same = customer("C-SAME", "Asha Rani Verma");
        coreKnows("LR-SAME", same);
        legacyLogged("LR-SAME", LegacyAxisPrecheckMapping.request(same, "local-agreement", "x").toString());

        Map<String, Object> changed = customer("C-CHANGED", "Ravi Kumar");
        coreKnows("LR-CHANGED", changed);
        ObjectNode logged = (ObjectNode) LegacyAxisPrecheckMapping.request(changed, "local-agreement", "x");
        logged.put("mobile_number", "9111111234");          // e.g. the customer changed their phone since
        legacyLogged("LR-CHANGED", logged.toString());

        legacyLogged("LR-TRUNCATED", "{\"agreement_id\":\"local-agre");

        assertThat(parity()).isEqualTo(1);
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("MATCH  LR-SAME")
                .contains("DIFF   LR-CHANGED").contains("mobile_number: legacy=\"XXXXXX1234\" ours=\"XXXXXX0099\"")
                .contains("ERROR  LR-TRUNCATED").contains("not JSON")
                .contains("cases: 3  match: 1  diff: 1  error: 1")
                .doesNotContain("9111111234");
        assertThat(core.getAllServeEvents()).allSatisfy(e -> assertThat(e.getRequest().getUrl()).startsWith("/api/support"));
        assertThat(lendercore.listCollectionNames().into(new java.util.ArrayList<>())).containsExactly("LenderApiAuditLog");
    }

    @Test
    void oneLoanRequestAndAllMatchExitsZero() throws Exception {
        Map<String, Object> c = customer("C-1", "Madonna");
        coreKnows("LR-1", c);
        legacyLogged("LR-1", LegacyAxisPrecheckMapping.request(c, "local-agreement", "x").toString());
        legacyLogged("LR-OTHER", "{}");
        assertThat(parity("--loan-request-id", "LR-1")).isZero();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("cases: 1  match: 1");
    }

    @Test
    void theCapturedOperationCanNeverBeLive() {
        assertThat(parity("--live", "axis.precheck.invoke")).isEqualTo(2);
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("can never run live");
    }
}
