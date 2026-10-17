package com.rupeek.l1.ops;

import com.rupeek.l1.model.Resource;

import java.util.Map;

/**
 * Everything an executor needs besides the operation and its parameters. Built by the engine per call.
 *
 * @param resource the operation's resource, or null for MAP/GEN without one
 * @param baseUrl  resolved base URL for HTTP resources in the active environment, else null
 * @param env      non-secret values of the resource for the active environment
 * @param sys      engine-supplied values: {@code now} (Instant, fixed per attempt), {@code caseId}, {@code runNo}
 * @param recorder filled by the executor with what was sent and received
 */
public record ExecContext(
        Resource resource,
        String baseUrl,
        Map<String, Object> env,
        Map<String, Object> sys,
        CallRecorder recorder) {

    public ExecContext {
        env = env == null ? Map.of() : env;
        sys = sys == null ? Map.of() : sys;
        recorder = recorder == null ? new CallRecorder() : recorder;
    }
}
