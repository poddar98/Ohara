package org.example.ohara.auth.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow against the real Postgres (ohara_test) with Flyway migrations applied.
 * Each test uses a fresh email so runs don't collide.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String registerJson(String email) {
        return """
            {"firstName":"Ada","lastName":"Lovelace","email":"%s","password":"secret1234"}
            """.formatted(email);
    }

    private static String refreshJson(String refreshToken) {
        return """
            {"refreshToken":"%s"}
            """.formatted(refreshToken);
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @Test
    void registerThenAccessProtectedEndpointAndRotateRefreshToken() throws Exception {
        String email = newEmail();
        MvcResult registered = register(email);
        String accessToken = field(registered, "$.accessToken");
        String refreshToken = field(registered, "$.refreshToken");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));

        // A refresh token must not authenticate API calls.
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshToken))
            .andExpect(status().is4xxClientError());

        MvcResult rotated = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(refreshToken)))
            .andExpect(status().isOk())
            .andReturn();
        String newRefreshToken = field(rotated, "$.refreshToken");

        // Reusing the old refresh token is rejected...
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(refreshToken)))
            .andExpect(status().isBadRequest());

        // ...and reuse revokes every active session, including the newly issued one.
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(newRefreshToken)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult registered = register(newEmail());
        String refreshToken = field(registered, "$.refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(refreshToken)))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(refreshToken)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().is4xxClientError());
    }
}
