package com.juanperuzzo.job_hunter.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Service-token authentication for self-hosted bot API access (issue #47, simplified
 * for the self-hosted bot-only mode). The only authentication mechanism: every
 * request must carry {@code X-Bot-Token: <secret>} matching {@code bot.service.api-key}.
 *
 * <p>Fail-fast at startup: if {@code bot.service.api-key} is blank, the application
 * context does not start (consistent with {@code HERMES_API_KEY}).
 *
 * <p>Every request without a valid token answers 401. No JWT, no user accounts.
 */
@Component
public class BotTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BotTokenFilter.class);

    private final String apiKey;

    public BotTokenFilter(@Value("${bot.service.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("bot.service.api-key must be set (fail-fast, like HERMES_API_KEY)");
        }
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Allow health/actuator probes without a token
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") || path.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-Bot-Token");
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"missing or invalid X-Bot-Token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
