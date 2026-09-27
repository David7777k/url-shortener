package io.github.david7777k.trimly.ratelimit;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // A small bucket that refills slowly, so a burst and its refusal both
        // happen inside a test rather than after a minute of waiting.
        "trimly.rate-limit.capacity=5",
        "trimly.rate-limit.refill-per-second=0.5"
})
class RateLimitTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsABurstUpToCapacity() throws Exception {
        for (int i = 0; i < 5; i++) {
            createAs("burst-client").andExpect(status().isCreated());
        }
    }

    @Test
    void refusesOnceTheBucketIsEmpty() throws Exception {
        for (int i = 0; i < 5; i++) {
            createAs("flood-client").andExpect(status().isCreated());
        }

        createAs("flood-client")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }

    @Test
    void countsClientsSeparately() throws Exception {
        for (int i = 0; i < 5; i++) {
            createAs("noisy").andExpect(status().isCreated());
        }
        createAs("noisy").andExpect(status().isTooManyRequests());

        // One client exhausting its bucket must not affect anybody else.
        createAs("quiet").andExpect(status().isCreated());
    }

    @Test
    void reportsRemainingTokens() throws Exception {
        createAs("counted")
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "4"));

        createAs("counted")
                .andExpect(header().string("X-RateLimit-Remaining", "3"));
    }

    @Test
    void retryAfterIsAtLeastOneSecond() throws Exception {
        for (int i = 0; i < 5; i++) {
            createAs("retry-client");
        }

        String retryAfter = createAs("retry-client")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        // Refill is 0.5/s, so one token takes two seconds. Retry-After has no
        // sub-second form, and a value of 0 would invite an immediate retry.
        assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void doesNotLimitTheRedirect() throws Exception {
        String code = createAs("redirect-client")
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // The hot path is left alone: limiting it would add a Redis round trip
        // to every visit, to guard against traffic the cache already absorbs.
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        }
    }

    private ResultActions createAs(String apiKey) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"targetUrl": "https://example.com"}
                        """));
    }
}
