package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ManagementAccessTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;

    private String[] create() throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return new String[] {
                body.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1"),
                body.replaceAll(".*\"managementToken\"\\s*:\\s*\"([^\"]+)\".*", "$1")};
    }

    @Test
    void onlyTheIssuedTokenCanReadManageAndInspectTheLink() throws Exception {
        String[] a = create();
        String[] b = create();
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                get("/api/v1/links/" + a[0]), get("/api/v1/links/" + a[0] + "/stats"),
                delete("/api/v1/links/" + a[0])}) {
            mvc.perform(request).andExpect(status().isNotFound());
            mvc.perform(request.header("X-Management-Token", b[1])).andExpect(status().isNotFound());
        }
        mvc.perform(get("/" + a[0])).andExpect(status().isFound());
        mvc.perform(get("/api/v1/links/" + a[0]).header("X-Management-Token", a[1]))
                .andExpect(status().isOk()).andExpect(jsonPath("$.managementToken").doesNotExist());
        mvc.perform(get("/api/v1/links/" + a[0] + "/stats").header("X-Management-Token", a[1]))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select management_token_hash from link where code = ?",
                String.class, a[0])).hasSize(64).isNotEqualTo(a[1]);
        mvc.perform(delete("/api/v1/links/" + a[0]).header("X-Management-Token", a[1]))
                .andExpect(status().isNoContent());
        mvc.perform(get("/" + a[0])).andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidStatisticsWindows() throws Exception {
        String[] link = create();
        for (int days : new int[] {-1, 0, 366}) {
            mvc.perform(get("/api/v1/links/" + link[0] + "/stats").param("days", "" + days)
                    .header("X-Management-Token", link[1])).andExpect(status().isBadRequest());
        }
    }

    @Test
    void malformedCacheEntryFallsBackToStoredLink() throws Exception {
        String[] link = create();
        for (String malformed : new String[] {"broken", "abc|https://example.org", "0|https://example.org", "1|javascript:alert(1)"}) {
            redisTemplate.opsForValue().set("link:" + link[0], malformed);
            mvc.perform(get("/" + link[0])).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com"));
        }
    }
}
