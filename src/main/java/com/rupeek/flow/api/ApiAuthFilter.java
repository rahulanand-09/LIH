package com.rupeek.flow.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Static-secret auth: X-API-Key for /flows, /cases, /admin; X-Callback-Secret for /v1/callback (skipped when no
 * callback secret is configured). Everything else (e.g. /actuator/health) is open. Constant-time compare.
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiAuthFilter.class);

    private final String apiKey;
    private final String callbackSecret;
    private final boolean enabled;

    public ApiAuthFilter(String apiKey, String callbackSecret, boolean enabled) {
        this.apiKey = apiKey;
        this.callbackSecret = callbackSecret;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean ok = true;
        if (path.startsWith("/v1/callback")) {
            ok = callbackSecret.isBlank() || matches(callbackSecret, req.getHeader("X-Callback-Secret"));
        } else if (enabled && (path.startsWith("/flows") || path.startsWith("/cases") || path.startsWith("/admin"))) {
            ok = matches(apiKey, req.getHeader("X-API-Key"));
        }
        if (!ok) {
            LOG.warn("Unauthorized {} {}", req.getMethod(), path);
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"missing or invalid credentials\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean matches(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }
}
