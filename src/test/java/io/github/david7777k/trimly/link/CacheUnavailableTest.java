package io.github.david7777k.trimly.link;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * <p>Redis is pointed at a port nothing listens on, rather than stopping the
 * shared container - stopping it would give the next test a different mapped
 * port and break every class after this one.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=100ms",
        "spring.data.redis.connect-timeout=100ms"
})
class CacheUnavailableTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
