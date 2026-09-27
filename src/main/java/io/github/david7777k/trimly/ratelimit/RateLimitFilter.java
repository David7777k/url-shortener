package io.github.david7777k.trimly.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the rate limit to link creation.
 *
 * <p>Only creation. The redirect is the hot path and is cheap - limiting it
 * would add a Redis round trip to every visit in order to protect against
 * traffic the cache already absorbs. Creation writes a row, so that is where
 * the limit belongs.
 */
@Component
@ConditionalOnProperty(name = "trimly.rate-limit.enabled", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LIMITED_PATH = "/api/v1/links";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && LIMITED_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RateLimiter.Decision decision = rateLimiter.check(clientId(request));

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // Retry-After tells a well-behaved client when to come back, instead of
        // leaving it to guess and hammer.
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests",\
                "status":429,"detail":"Rate limit exceeded, retry in %d seconds"}\
                """.formatted(decision.retryAfterSeconds()));
    }

    /**
     * Identifies the caller by API key, falling back to the remote address.
     *
     * <p>{@code X-Forwarded-For} is deliberately not trusted. Behind a proxy it
     * is the real client address, but it is a header a client can set, so
     * honouring it without knowing the proxy is in front means anyone can
     * change their identity and reset their own bucket. Behind a real proxy
     * this needs {@code server.forward-headers-strategy} configured, which is
     * a deployment decision rather than something to assume.
     */
    private static String clientId(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        return "ip:" + request.getRemoteAddr();
    }
}
