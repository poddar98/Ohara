package org.example.ohara.auth.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final String COOKIE = "refresh_token";

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

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String refreshCookie(MvcResult result) {
        return result.getResponse().getCookie(COOKIE).getValue();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)))
            .andReturn();
    }

    @Test
    void refreshCookieIsHttpOnlyAndNotInBody() throws Exception {
        MvcResult registered = register(newEmail());

        Cookie cookie = registered.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(registered.getResponse().getContentAsString()).doesNotContain("refreshToken");
    }

    @Test
    void registerThenAccessProtectedEndpointAndRotateRefreshToken() throws Exception {
        String email = newEmail();
        MvcResult registered = register(email);
        String refreshToken = refreshCookie(registered);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken(registered)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));

        // A refresh token must not authenticate API calls.
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshToken))
            .andExpect(status().is4xxClientError());

        MvcResult rotated = refresh(refreshToken);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String newRefreshToken = refreshCookie(rotated);

        // Reusing the old refresh token is rejected...
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(400);

        // ...and reuse revokes every active session, including the newly issued one.
        assertThat(refresh(newRefreshToken).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void refreshWithoutCookieIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        String refreshToken = refreshCookie(register(newEmail()));

        MvcResult loggedOut = mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, refreshToken)))
            .andExpect(status().isNoContent())
            .andReturn();
        Cookie cleared = loggedOut.getResponse().getCookie(COOKIE);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().is4xxClientError());
    }
}
