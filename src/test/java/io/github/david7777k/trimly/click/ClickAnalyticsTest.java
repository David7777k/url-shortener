package io.github.david7777k.trimly.click;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // The scheduler is off so each test decides when the queue is drained.
        // Otherwise a flush landing mid-assertion would make these flaky.
        "trimly.clicks.enabled=false"
})
class ClickAnalyticsTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClickRecorder recorder;

    @Autowired
    private ClickWriter writer;

    // --- the redirect does not wait -------------------------------------------

    @Test
    void redirectQueuesTheClickWithoutWritingIt() throws Exception {
        String code = create("https://example.com");

        mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());

        // Nothing in the table yet: the redirect only queued the event.
        assertThat(clickRows()).isZero();
        assertThat(recorder.queued()).isEqualTo(1);

        writer.flushOnce();
        assertThat(clickRows()).isEqualTo(1);
    }

    @Test
    void writesQueuedClicksInOneBatch() throws Exception {
        String code = create("https://example.com");

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(get("/{code}", code)).andExpect(status().isFound());
        }

        assertThat(clickRows()).isZero();
        assertThat(writer.flushOnce()).isEqualTo(50);
        assertThat(clickRows()).isEqualTo(50);
    }

    @Test
    void recordsRefererAndUserAgent() throws Exception {
        String code = create("https://example.com");

        mockMvc.perform(get("/{code}", code)
                        .header(HttpHeaders.REFERER, "https://news.example.org/article")
                        .header(HttpHeaders.USER_AGENT, "TestAgent/1.0"))
                .andExpect(status().isFound());

        writer.flushOnce();

        assertThat(jdbcTemplate.queryForObject(
                "select referrer from click_event", String.class))
                .isEqualTo("https://news.example.org/article");
        assertThat(jdbcTemplate.queryForObject(
                "select user_agent from click_event", String.class))
                .isEqualTo("TestAgent/1.0");
    }

    @Test
    void truncatesOverlongHeaders() throws Exception {
        String code = create("https://example.com");

        mockMvc.perform(get("/{code}", code)
                        .header(HttpHeaders.USER_AGENT, "x".repeat(5_000)))
                .andExpect(status().isFound());

        writer.flushOnce();

        // The column has a CHECK on length; an untruncated header would make
        // the whole batch fail rather than just this row.
        assertThat(jdbcTemplate.queryForObject(
                "select length(user_agent) from click_event", Integer.class))
                .isEqualTo(512);
    }

    @Test
    void doesNotRecordAClickForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/nosuch4")).andExpect(status().isNotFound());

        writer.flushOnce();
        assertThat(clickRows()).isZero();
    }

    // --- what happens under pressure ------------------------------------------

    @Test
    void dropsEventsRatherThanBlockingWhenTheQueueIsFull() {
        ClickRecorder small = new ClickRecorder(java.time.Clock.systemUTC(), 10);

        for (int i = 0; i < 100; i++) {
            small.record(1L, null, null);
        }

        // Ten kept, ninety discarded - and counted, because a silently lossy
        // counter is worse than a visibly lossy one.
        assertThat(small.queued()).isEqualTo(10);
        assertThat(small.droppedCount()).isEqualTo(90);
    }

    // --- statistics ------------------------------------------------------------

    @Test
    void reportsTotalClicks() throws Exception {
        String code = create("https://example.com");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{code}", code));
        }
        writer.flushOnce();

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.totalClicks").value(3));
    }

    @Test
    void groupsClicksByDay() throws Exception {
        String code = create("https://example.com");
        long linkId = linkId(code);

        jdbcTemplate.update("""
                insert into click_event (link_id, clicked_at)
                values (?, now() - interval '1 day'), (?, now() - interval '1 day'), (?, now())
                """, linkId, linkId, linkId);

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byDay", hasSize(2)))
                .andExpect(jsonPath("$.byDay[0].clicks").value(2))
                .andExpect(jsonPath("$.byDay[1].clicks").value(1));
    }

    @Test
    void groupsClicksWithoutARefererAsDirect() throws Exception {
        String code = create("https://example.com");
        long linkId = linkId(code);

        jdbcTemplate.update("""
                insert into click_event (link_id, clicked_at, referrer)
                values (?, now(), 'https://news.example.org'),
                       (?, now(), 'https://news.example.org'),
                       (?, now(), null)
                """, linkId, linkId, linkId);

        mockMvc.perform(get("/api/v1/links/{code}/stats", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topReferrers[0].source").value("https://news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                // Traffic with no referrer is a category, not missing data.
                .andExpect(jsonPath("$.topReferrers[1].source").value("direct"));
    }

    @Test
    void statsForAnUnknownCodeAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/links/nosuch5/stats"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingALinkRemovesItsClicks() throws Exception {
        String code = create("https://example.com");
        mockMvc.perform(get("/{code}", code));
        writer.flushOnce();
        assertThat(clickRows()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/links/{code}", code))
                .andExpect(status().isNoContent());

        // ON DELETE CASCADE, rather than orphaned rows nothing will ever read.
        assertThat(clickRows()).isZero();
    }

    // --- helpers ---------------------------------------------------------------

    private int clickRows() {
        return jdbcTemplate.queryForObject("select count(*) from click_event", Integer.class);
    }

    private long linkId(String code) {
        return jdbcTemplate.queryForObject(
                "select id from link where code = ?", Long.class, code);
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
