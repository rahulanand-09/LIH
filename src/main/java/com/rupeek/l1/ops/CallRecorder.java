package com.rupeek.l1.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an executor actually sent and received, for audit and tracing. Executors fill it; interceptors read it.
 * Never holds secrets: auth headers are not recorded.
 */
public final class CallRecorder {

    private String method;
    private String target;
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();
    private Object requestBody;
    private Integer responseStatus;
    private Object responseBody;

    public void request(String method, String target, Map<String, String> headers, Object body) {
        this.method = method;
        this.target = target;
        this.requestHeaders.clear();
        if (headers != null) {
            this.requestHeaders.putAll(headers);
        }
        this.requestBody = body;
    }

    public void response(Integer status, Object body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public String method() {
        return method;
    }

    /** URL for HTTP, {@code mongo://<db>/<collection>} for MONGO. */
    public String target() {
        return target;
    }

    public Map<String, String> requestHeaders() {
        return requestHeaders;
    }

    public Object requestBody() {
        return requestBody;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public Object responseBody() {
        return responseBody;
    }
}
