package org.example.ohara.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ohara.rate-limit.enabled=true")
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired MockMvc mockMvc;

    private int login(String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void loginAttemptsArePerIpAndRejectedBeyondTheLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("198.51.100.1");
        }

        mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("198.51.100.1"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.com\",\"password\":\"whatever-1\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("rate_limited"));

        // Another client is unaffected, and so are other endpoints for the throttled client.
        org.assertj.core.api.Assertions.assertThat(login("198.51.100.2")).isEqualTo(401);
        mockMvc.perform(get("/api/health").with(r -> { r.setRemoteAddr("198.51.100.1"); return r; }))
            .andExpect(status().isOk());
    }

    @Test
    void invitationLookupsAreThrottled() throws Exception {
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/invitations/{t}", "guess-" + i).with(r -> { r.setRemoteAddr("198.51.100.9"); return r; }))
                .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/invitations/{t}", "guess-21").with(r -> { r.setRemoteAddr("198.51.100.9"); return r; }))
            .andExpect(status().isTooManyRequests());
    }
}
