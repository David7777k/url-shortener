package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LinkControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- creating -------------------------------------------------------------

    @Test
    void createsLinkAndReturnsItsCode() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "https://example.com/a/long/path?with=query"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(Matchers.matchesPattern("[0-9a-zA-Z]{7}")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/a/long/path?with=query"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    void generatesADifferentCodeForTheSameTarget() throws Exception {
        String first = create("https://example.com");
        String second = create("https://example.com");

        // Codes are random, not derived from the URL: shortening the same
        // target twice must not produce the same code.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsBlankTarget() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.targetUrl").exists());
    }

    @Test
    void rejectsRelativeTarget() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "/just/a/path"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Target must be an absolute URL"));
    }

    @Test
    void rejectsNonHttpSchemes() throws Exception {
        // A redirect to javascript: would let this service launder somebody
        // else script under its own name.
        String[] targets = {
                "javascript:alert(1)",
                "data:text/html,<h1>hi</h1>",
                "file:///etc/passwd"
        };

        for (String target : targets) {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUrl\": \"%s\"}".formatted(target)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsTargetPointingBackAtThisService() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "http://localhost:8080/abc1234"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Target must not point back at this service"));
    }

    @Test
    void rejectsExpiryInThePast() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "https://example.com", "expiresAt": "2020-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresAt").exists());
    }

    // --- reading --------------------------------------------------------------

    @Test
    void returnsNotFoundForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/links/nosuch1"))
                .andExpect(status().isNotFound());
    }

    // --- redirecting ----------------------------------------------------------

    @Test
    void redirectsToTheTarget() throws Exception {
        String code = create("https://example.com/destination");

        mockMvc.perform(get("/{code}", code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void redirectIsTemporaryNotPermanent() throws Exception {
        String code = create("https://example.com");

        // 301 would be cached by the browser indefinitely, making the link
        // impossible to retarget and silently stopping click counting.
        mockMvc.perform(get("/{code}", code))
                .andExpect(status().is(302));
    }

    @Test
    void unknownCodeRedirectAnswersNotFound() throws Exception {
        mockMvc.perform(get("/nosuch1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredLinkAnswersGone() throws Exception {
        // Inserted directly: the API refuses to create an already-expired link,
        // which is correct, so this state has to be set up behind it.
        jdbcTemplate.update("""
                insert into link (code, target_url, created_at, expires_at)
                values ('exp1234', 'https://example.com', now() - interval '2 days',
                        now() - interval '1 day')
                """);

        mockMvc.perform(get("/exp1234"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Link expired"));
    }

    @Test
    void linkWithFutureExpiryStillRedirects() throws Exception {
        jdbcTemplate.update("""
                insert into link (code, target_url, expires_at)
                values ('fut1234', 'https://example.com', now() + interval '1 day')
                """);

        mockMvc.perform(get("/fut1234"))
                .andExpect(status().isFound());
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
