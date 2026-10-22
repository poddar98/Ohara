package org.example.ohara.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.example.ohara.common.web.ApiExceptionHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Per-client-IP limits on unauthenticated endpoints that attackers can hammer (credential stuffing, email bombing,
 * token guessing). Per-account protection is separate (login lockout, per-email resend cooldowns).
 * Runs before Spring Security so rejected requests cost almost nothing. Behind a proxy, configure
 * {@code server.forward-headers-strategy} so the client address is the real one.
 * Kept outside the exposed {@code common.web} package: Modulith observability proxies exposed beans, and a proxied
 * servlet filter loses its inherited logger.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    /** {@code pathPrefix} matched with {@code startsWith}; {@code method} null = any. */
    record Rule(String name, String method, String pathPrefix, int capacity, Duration period) {

        boolean matches(HttpServletRequest request) {
            return (method == null || method.equalsIgnoreCase(request.getMethod()))
                && request.getRequestURI().startsWith(pathPrefix);
        }
    }

    static final List<Rule> RULES = List.of(
        new Rule("login", "POST", "/api/auth/login", 10, Duration.ofMinutes(1)),
        new Rule("register", "POST", "/api/auth/register", 5, Duration.ofMinutes(1)),
        new Rule("account-email", "POST", "/api/auth/forgot-password", 5, Duration.ofMinutes(1)),
        new Rule("account-email", "POST", "/api/auth/verify-email/resend", 5, Duration.ofMinutes(1)),
        new Rule("account-token", "POST", "/api/auth/verify-email", 10, Duration.ofMinutes(1)),
        new Rule("account-token", "POST", "/api/auth/reset-password", 10, Duration.ofMinutes(1)),
        new Rule("refresh", "POST", "/api/auth/refresh", 30, Duration.ofMinutes(1)),
        new Rule("invitation", null, "/api/invitations/", 20, Duration.ofMinutes(1)));

    private final TokenBucketLimiter limiter = new TokenBucketLimiter();
    private final boolean enabled;

    public RateLimitFilter(@Value("${ohara.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : RULES) {
            if (rule.matches(request)) {
                TokenBucketLimiter.Decision decision =
                    limiter.tryAcquire(rule.name() + ":" + request.getRemoteAddr(), rule.capacity(), rule.period());
                if (!decision.allowed()) {
                    reject(request, response, decision.retryAfterSeconds());
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
            {"type":"%srate-limited","title":"Too Many Requests","status":429,"code":"rate_limited",\
            "detail":"Too many requests; try again in %d seconds","instance":"%s"}"""
            .formatted(ApiExceptionHandler.TYPE_BASE, retryAfterSeconds, request.getRequestURI().replace("\"", "")));
    }
}
