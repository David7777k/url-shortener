package io.github.david7777k.trimly.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitIdentityTest {
    @Test
    void unverifiedHeadersCannotChangeTheClientIdentity() throws Exception {
        var clients = new java.util.ArrayList<String>();
        RateLimiter limiter = new RateLimiter(null, java.time.Clock.systemUTC(), 20, 1, 3600, true) {
            @Override public Decision check(String clientId) {
                clients.add(clientId);
                return new Decision(true, 20, 19, 0);
            }
        };
        var filter = new RateLimitFilter(limiter);
        for (String key : new String[] {"first", "second"}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/links");
            request.setRemoteAddr("192.0.2.10");
            request.addHeader("X-API-Key", key);
            request.addHeader("X-Forwarded-For", key);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        assertThat(clients).containsExactly("ip:192.0.2.10", "ip:192.0.2.10");
    }
}

