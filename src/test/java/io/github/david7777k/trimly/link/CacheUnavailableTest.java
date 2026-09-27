package io.github.david7777k.trimly.link;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service must survive Redis being gone.
 *
 * <p>A cache is an optimisation. If losing it takes the service down, it has
 * stopped being an optimisation and become a second thing that can fail.
 *
 * <p>The template is replaced with one that throws, rather than pointing the
 * configuration at a dead port. A first attempt did the latter and passed while
 * proving nothing: {@code DynamicPropertyRegistrar} outranks
 * {@code @TestPropertySource}, so the container host and port won and Redis was
 * reachable throughout.
 */
@AutoConfigureMockMvc
class CacheUnavailableTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StringRedisTemplate brokenRedis;

    @BeforeEach
    void makeEveryRedisCallFail() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> failing = mock(ValueOperations.class);

        when(failing.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        // The Duration overload is named explicitly: ValueOperations has a
        // second three-argument set() taking a Consumer, and a bare any()
        // cannot tell them apart.
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(failing).set(anyString(), anyString(), any(Duration.class));

        when(brokenRedis.opsForValue()).thenReturn(failing);
        when(brokenRedis.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
    }

    @Test
    void stillCreatesLinks() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "https://example.com/created"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void stillRedirects() throws Exception {
        String code = create("https://example.com/still-works");

        // Slower - every resolve now reaches PostgreSQL - but correct.
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/still-works"));

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound());
    }

    @Test
    void stillReportsUnknownCodes() throws Exception {
        mockMvc.perform(get("/nosuch3"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stillDeletes() throws Exception {
        String code = create("https://example.com/doomed");

        // The eviction cannot reach Redis, which must not fail the delete.
        mockMvc.perform(delete("/api/v1/links/{code}", code))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isNotFound());
    }

    private String create(String target) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\": \"%s\"}".formatted(target)))
                .andExpect(status().isCreated())
                .andReturn();

        return result.getResponse().getContentAsString()
                .replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
