package com.rupeek.l1.intercept;

import com.rupeek.l1.ops.TransportException;

import java.util.Map;

/**
 * Cross-cutting work around every operation (tracing, metrics, audit). An interceptor must rethrow the
 * operation's own failures unchanged and must never fail a call because of its own errors.
 */
public interface OperationInterceptor {

    Map<String, Object> around(OperationCall call, Proceed next) throws TransportException;

    @FunctionalInterface
    interface Proceed {
        Map<String, Object> proceed() throws TransportException;
    }
}
