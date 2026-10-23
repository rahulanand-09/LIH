package com.rupeek.flow.api;

import com.rupeek.flow.expr.ExprCompileException;
import com.rupeek.flow.registry.store.FlowConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** One JSON error shape for the whole API; never a stack trace. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.getMessage(), e.workflowId());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            UncheckedIOException.class, ExprCompileException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        LOG.warn("Rejected request: {}", e.getClass().getSimpleName());
        return body(HttpStatus.BAD_REQUEST, message(e), null);
    }

    @ExceptionHandler(FlowConfigException.class)
    ResponseEntity<Map<String, Object>> notPublished(FlowConfigException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {   // framework 404/405/415: keep its status
            HttpStatus s = HttpStatus.valueOf(er.getStatusCode().value());
            return body(s, s.getReasonPhrase(), null);
        }
        LOG.error("Unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error", null);
    }

    /** First line only: no payload echo, no stack. */
    private static String message(Exception e) {
        if (e instanceof HttpMessageNotReadableException) {
            return "malformed or missing JSON body";
        }
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, String workflowId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", status.getReasonPhrase());
        b.put("message", message);
        if (workflowId != null) {
            b.put("workflowId", workflowId);
        }
        return ResponseEntity.status(status).body(b);
    }
}
