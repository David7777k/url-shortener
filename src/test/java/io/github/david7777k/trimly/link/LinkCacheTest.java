package io.github.david7777k.trimly.link;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LinkCacheTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- the cache does its job ----------------------------------------------

    @Test
    void populatesTheCacheOnFirstResolve() throws Exception {
        String code = create("https://example.com/target");

        assertThat(redisTemplate.opsForValue().get("link:" + code))
                .as("nothing is cached before the first resolve")
                .isNull();

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        // Stored as "id|url": recording a click needs the id, and looking it
        // up separately would be the query the cache exists to avoid.
        assertThat(redisTemplate.opsForValue().get("link:" + code))
                .endsWith("|https://example.com/target")
                .matches("^\\d+\\|.+$");
    }

    @Test
    void servesTheSecondResolveWithoutTouchingTheDatabase() throws Exception {
        String code = create("https://example.com/cached");
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        // Delete the row behind the service. A cached resolve must still work,
        // which is the only way to prove the answer came from Redis.
        jdbcTemplate.update("delete from link where code = ?", code);

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/cached"));
    }

    @Test
    void remembersThatACodeDoesNotExist() throws Exception {
        mockMvc.perform(get("/nosuch1")).andExpect(status().isNotFound());

        // Without negative caching a flood of junk codes reaches the database
        // on every request - the cache would be useless exactly when needed.
        assertThat(redisTemplate.opsForValue().get("link:nosuch1")).isNotNull();

        mockMvc.perform(get("/nosuch1")).andExpect(status().isNotFound());
    }

    @Test
    void doesNotCacheLinksThatExpire() throws Exception {
        jdbcTemplate.update("""
                insert into link (code, target_url, expires_at)
                values ('exp2345', 'https://example.com', now() + interval '1 day')
                """);

        mockMvc.perform(get("/exp2345")).andExpect(status().isFound());

        assertThat(redisTemplate.opsForValue().get("link:exp2345"))
                .as("caching it would mean serving it after it lapses")
                .isNull();
    }

    // --- invalidation ---------------------------------------------------------

    @Test
    void deletingALinkDropsItFromTheCache() throws Exception {
        String code = create("https://example.com/doomed");
        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        assertThat(redisTemplate.opsForValue().get("link:" + code)).isNotNull();

        mockMvc.perform(delete("/api/v1/links/{code}", code))
                .andExpect(status().isNoContent());

        assertThat(redisTemplate.opsForValue().get("link:" + code)).isNull();
        mockMvc.perform(get("/{code}", code)).andExpect(status().isNotFound());
    }

    @Test
    void creatingALinkClearsAnyRememberedMiss() throws Exception {
        // Simulate somebody having asked for this code before it was issued.
        redisTemplate.opsForValue().set("link:premade", "\u0000missing");

        jdbcTemplate.update(
                "insert into link (code, target_url) values ('premade', 'https://example.com')");
        // The service evicts on create; done here by hand because the code is
        // random and cannot be chosen through the API.
        redisTemplate.delete("link:premade");

        mockMvc.perform(get("/premade")).andExpect(status().isFound());
    }

    // --- helpers --------------------------------------------------------------

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
