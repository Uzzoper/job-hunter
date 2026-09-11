package com.juanperuzzo.job_hunter.infrastructure.security;

import com.juanperuzzo.job_hunter.domain.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Optional service-token authentication for self-hosted bot API access (issue #47).
 * <p>
 * When {@code bot.service.api-key} is non-blank and {@code bot.service.owner-user-id}
 * resolves to a positive id, an {@code X-Bot-Token} header matching the key exactly
 * authenticates the caller as a bot principal scoped to the configured owner user.
 * The principal is the domain {@code User} (the same kind the JWT path sets), so
 * {@link CurrentUserService#getCurrentUserId()} returns the owner id and every
 * per-user query downstream works unchanged.
 * <p>
 * Every failure mode falls through to the normal JWT chain, preserving existing
 * behavior byte-identically (401 without a JWT, never 500, never a bypass):
 * <ul>
 *   <li>feature disabled (blank api-key or missing owner id) → no-op</li>
 *   <li>header absent or not an exact match → no-op</li>
 * </ul>
 * The token has no expiry; revocation is rotating {@code bot.service.api-key} and
 * restarting the application (values are read once at construction).
 * <p>
 * The filter is only ever registered inside the {@code SecurityFilterChain}
 * (see {@link SecurityConfig}); its servlet auto-registration is disabled via a
 * {@code FilterRegistrationBean} so it runs <em>after</em> Spring Security sets up
 * the request context, keeping the principal visible to the authorization step.
 */
@Component
public class BotTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BotTokenFilter.class);

    private final String apiKey;
    private final Long ownerUserId;

    public BotTokenFilter(
            @Value("${bot.service.api-key:}") String apiKey,
            @Value("${bot.service.owner-user-id:}") Long ownerUserId) {
        this.apiKey = apiKey;
        this.ownerUserId = ownerUserId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isEnabled()) {
            authenticateIfTokenMatches(request);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isEnabled() {
        return apiKey != null
                && !apiKey.isBlank()
                && ownerUserId != null
                && ownerUserId > 0;
    }

    private void authenticateIfTokenMatches(HttpServletRequest request) {
        String provided = request.getHeader("X-Bot-Token");
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            return; // fall through to the JWT chain
        }
        // Mirrors the JWT path: the SecurityContext holds the domain User, so
        // CurrentUserService.getCurrentUserId() resolves the bot's owner id.
        // The email/name are synthetic principal fields; the owner identity itself
        // comes from configuration (never hardcoded to a specific user).
        var botPrincipal = new User(ownerUserId, "bot@jobhunter.local", "bot", "");
        var authentication = new UsernamePasswordAuthenticationToken(
                botPrincipal, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("Authenticated bot principal as user {}", ownerUserId);
    }

    /** Constant-time comparison of the service token to avoid timing leaks. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
