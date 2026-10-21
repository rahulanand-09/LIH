package com.rupeek.l1.intercept;

import com.rupeek.l1.ops.TransportException;

import java.util.List;
import java.util.Map;

/**
 * Runs interceptors around the executor, first in the list outermost.
 * Production order: tracing, metrics, audit, then the executor.
 */
public final class InterceptorChain {

    private final List<OperationInterceptor> interceptors;

    public InterceptorChain(List<OperationInterceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    public Map<String, Object> invoke(OperationCall call, OperationInterceptor.Proceed executor) throws TransportException {
        return invoke(0, call, executor);
    }

    private Map<String, Object> invoke(int index, OperationCall call, OperationInterceptor.Proceed executor)
            throws TransportException {
        if (index == interceptors.size()) {
            return executor.proceed();
        }
        return interceptors.get(index).around(call, () -> invoke(index + 1, call, executor));
    }
}
