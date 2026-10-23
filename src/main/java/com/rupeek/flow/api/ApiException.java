package com.rupeek.flow.api;

import org.springframework.http.HttpStatus;

/** A request failure with a deliberate HTTP status; rendered as {error, message, workflowId?}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String workflowId;

    public ApiException(HttpStatus status, String message, String workflowId) {
        super(message);
        this.status = status;
        this.workflowId = workflowId;
    }

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public HttpStatus status() { return status; }
    public String workflowId() { return workflowId; }
}
