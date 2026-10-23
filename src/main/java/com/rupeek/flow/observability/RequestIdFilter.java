package com.rupeek.flow.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a {@code requestId} MDC key on every HTTP request thread and echoes it back as {@code X-Request-Id}.
 * An inbound {@code X-Request-Id} is honoured only if it is a short, plain token (no log-injection characters);
 * otherwise a fresh UUID is generated. Independent of the api package: anything logging on the request thread
 * (controllers, auth filters) gets the key for free.
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String id = inbound != null && SAFE.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        try (LogContext ignored = LogContext.with(LogContext.REQUEST_ID, id)) {
            chain.doFilter(request, response);
        }
    }
}
