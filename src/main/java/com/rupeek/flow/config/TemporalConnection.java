package com.rupeek.flow.config;

import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Builds the Temporal connection from {@code flow.temporal.*}; shared by the worker and the HTTP API's client. */
public final class TemporalConnection {

    private TemporalConnection() {
    }

    public static WorkflowServiceStubs stubs(FlowProperties.Temporal t) {
        WorkflowServiceStubsOptions.Builder b = WorkflowServiceStubsOptions.newBuilder().setTarget(t.getTarget());
        if (!t.getApiKey().isBlank()) {
            String key = t.getApiKey();
            b.setEnableHttps(true).addApiKey(() -> key);
        }
        if (!t.getTlsCertPath().isBlank()) {
            try (InputStream cert = new FileInputStream(t.getTlsCertPath());
                 InputStream key = new FileInputStream(t.getTlsKeyPath())) {
                b.setSslContext(SimpleSslContextBuilder.forPKCS8(cert, key).build());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read Temporal mTLS cert/key", e);
            }
        }
        return WorkflowServiceStubs.newServiceStubs(b.build());
    }
}
