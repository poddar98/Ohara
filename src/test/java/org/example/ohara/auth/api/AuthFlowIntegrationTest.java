package org.example.ohara.auth.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.infrastructure.InMemoryEmailSender;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flows against the real Postgres (ohara_test) with Flyway migrations applied.
 * Emails are queued as jobs; {@link #delivered} runs the job runner and reads the in-memory mock sender,
 * which is how tests follow verification and reset links.
 * Each test uses a fresh email so runs don't collide.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String COOKIE = "refresh_token";
    private static final String PASSWORD = "secret1234";
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEmailSender emails;

    @Autowired
    private JobRunner jobs;

    /** Delivers queued emails, then returns everything sent to {@code email}. */
    private List<EmailMessage> delivered(String email) {
        jobs.drain();
        return emails.sentTo(email);
    }

    private static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions register(String email) throws Exception {
        return postJson("/api/auth/register", """
            {"firstName":"Ada","lastName":"Lovelace","email":"%s","password":"%s","acceptTerms":true}
            """.formatted(email, PASSWORD));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", """
            {"email":"%s","password":"%s"}
            """.formatted(email, password));
    }

    private String tokenFromLastEmail(String email, String subjectFragment) {
        // Emails are delivered concurrently, so pick the latest one of the wanted kind rather than the latest overall.
        EmailMessage message = delivered(email).stream()
            .filter(m -> m.subject().contains(subjectFragment))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No '" + subjectFragment + "' email sent to " + email));
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions verify(String token) throws Exception {
        return postJson("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token));
    }

    /** Registers and verifies, returning the session issued by verification. */
    private MvcResult registeredAndVerified(String email) throws Exception {
        register(email).andExpect(status().isAccepted());
        return verify(tokenFromLastEmail(email, "Verify")).andExpect(status().isOk()).andReturn();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String refreshCookie(MvcResult result) {
        return result.getResponse().getCookie(COOKIE).getValue();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken))).andReturn();
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken));
    }

    // --- registration & verification ---------------------------------------------------------------

    @Test
    void registrationReturnsNoSessionAndSendsAVerificationEmail() throws Exception {
        String email = newEmail();

        MvcResult result = register(email)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("VERIFICATION_REQUIRED"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn();

        assertThat(result.getResponse().getCookie(COOKIE)).isNull();
        assertThat(tokenFromLastEmail(email, "Verify")).isNotBlank();
    }

    @Test
    void registeringAnExistingEmailLooksTheSameButSendsANotice() throws Exception {
        String email = newEmail();
        registeredAndVerified(email);

        register(email.toUpperCase())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("VERIFICATION_REQUIRED"));

        assertThat(delivered(email).getLast().subject()).contains("already have");
    }

    @Test
    void verificationActivatesTheAccountAndLinksAreSingleUse() throws Exception {
        String email = newEmail();
        register(email);
        String token = tokenFromLastEmail(email, "Verify");

        MvcResult verified = verify(token).andExpect(status().isOk()).andReturn();
        me(accessToken(verified))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.emailVerified").value(true));

        verify(token).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("verification_token_expired"));
        verify("not-a-real-token").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("verification_token_invalid"));
    }

    @Test
    void unverifiedAccountsCanSignInWithALimitedSession() throws Exception {
        String email = newEmail();
        register(email);

        String accessToken = accessToken(login(email, PASSWORD).andExpect(status().isOk()).andReturn());

        me(accessToken)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
            .andExpect(jsonPath("$.emailVerified").value(false));
        // Anything beyond /me needs a verified email.
        mockMvc.perform(get("/api/anything-else").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void resendWithinTheCooldownKeepsTheExistingLink() throws Exception {
        String email = newEmail();
        register(email);
        String first = tokenFromLastEmail(email, "Verify");

        postJson("/api/auth/verify-email/resend", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());

        // Within the cooldown no new email is sent, so the first link still works.
        assertThat(tokenFromLastEmail(email, "Verify")).isEqualTo(first);
        verify(first).andExpect(status().isOk());
    }

    // --- sessions ----------------------------------------------------------------------------------

    @Test
    void refreshCookieIsHttpOnlyAndNotInBody() throws Exception {
        MvcResult session = registeredAndVerified(newEmail());

        Cookie cookie = session.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(session.getResponse().getContentAsString()).doesNotContain("refreshToken");
    }

    @Test
    void accessProtectedEndpointAndRotateRefreshToken() throws Exception {
        String email = newEmail();
        MvcResult session = registeredAndVerified(email);
        String refreshToken = refreshCookie(session);

        me(accessToken(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));

        // A refresh token must not authenticate API calls.
        me(refreshToken).andExpect(status().isUnauthorized());

        MvcResult rotated = refresh(refreshToken);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String newRefreshToken = refreshCookie(rotated);

        // Reusing the old refresh token is rejected...
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
        // ...and reuse revokes every active session, including the newly issued one.
        assertThat(refresh(newRefreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshTokenReuseAlsoInvalidatesOutstandingAccessTokens() throws Exception {
        MvcResult session = registeredAndVerified(newEmail());
        String accessToken = accessToken(session);
        String refreshToken = refreshCookie(session);

        refresh(refreshToken);
        refresh(refreshToken); // reuse → all sessions revoked, auth_version bumped

        me(accessToken).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCookieIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("refresh_token_missing"));
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        String refreshToken = refreshCookie(registeredAndVerified(newEmail()));

        MvcResult loggedOut = mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, refreshToken)))
            .andExpect(status().isNoContent())
            .andReturn();
        Cookie cleared = loggedOut.getResponse().getCookie(COOKIE);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutAllEndsEverySession() throws Exception {
        String email = newEmail();
        MvcResult first = registeredAndVerified(email);
        MvcResult second = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + accessToken(second)))
            .andExpect(status().isNoContent());

        me(accessToken(first)).andExpect(status().isUnauthorized());
        assertThat(refresh(refreshCookie(first)).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(refreshCookie(second)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    // --- login ---------------------------------------------------------------------------------------

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        String email = newEmail();
        registeredAndVerified(email);

        login(email.toUpperCase(), PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void wrongPasswordIsUnauthorizedProblem() throws Exception {
        String email = newEmail();
        registeredAndVerified(email);

        login(email, "wrong-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void unknownEmailIsTheSameGenericUnauthorized() throws Exception {
        login(newEmail(), PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void fiveFailedLoginsLockTheAccountEvenForTheRightPassword() throws Exception {
        String email = newEmail();
        registeredAndVerified(email);

        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }

        login(email, PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    // --- password reset ------------------------------------------------------------------------------

    @Test
    void passwordResetReplacesThePasswordAndEndsExistingSessions() throws Exception {
        String email = newEmail();
        MvcResult session = registeredAndVerified(email);

        postJson("/api/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
        String resetToken = tokenFromLastEmail(email, "Reset");

        postJson("/api/auth/reset-password", """
            {"token":"%s","newPassword":"brand-new-pass"}
            """.formatted(resetToken)).andExpect(status().isNoContent());

        assertThat(delivered(email).getLast().subject()).contains("password was changed");
        me(accessToken(session)).andExpect(status().isUnauthorized());
        assertThat(refresh(refreshCookie(session)).getResponse().getStatus()).isEqualTo(401);
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, "brand-new-pass").andExpect(status().isOk());

        postJson("/api/auth/reset-password", """
            {"token":"%s","newPassword":"another-pass-1"}
            """.formatted(resetToken))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("reset_token_expired"));
    }

    @Test
    void resettingThePasswordAlsoVerifiesAPendingAccount() throws Exception {
        String email = newEmail();
        register(email);

        postJson("/api/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
        postJson("/api/auth/reset-password", """
            {"token":"%s","newPassword":"brand-new-pass"}
            """.formatted(tokenFromLastEmail(email, "Reset"))).andExpect(status().isNoContent());

        String accessToken = accessToken(login(email, "brand-new-pass").andExpect(status().isOk()).andReturn());
        me(accessToken).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void forgotPasswordForAnUnknownEmailLooksTheSameAndSendsNothing() throws Exception {
        String email = newEmail();

        postJson("/api/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());

        assertThat(delivered(email)).isEmpty();
    }

    // --- consent -------------------------------------------------------------------------------------

    @Test
    void registrationRequiresAcceptingTheTerms() throws Exception {
        postJson("/api/auth/register", """
            {"firstName":"Ada","lastName":"Lovelace","email":"%s","password":"%s"}
            """.formatted(newEmail(), PASSWORD))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[?(@.field == 'acceptTerms')]").exists());
    }

    @Test
    void consentsAreRecordedAtSignUpAndCanBeChanged() throws Exception {
        String accessToken = accessToken(registeredAndVerified(newEmail()));

        me(accessToken).andExpect(jsonPath("$.termsAccepted").value(true));
        mockMvc.perform(get("/api/users/me/consents").header("Authorization", "Bearer " + accessToken))
            .andExpect(jsonPath("$[?(@.type == 'TERMS')].granted").value(true))
            .andExpect(jsonPath("$[?(@.type == 'MARKETING_EMAIL')].granted").value(false));

        mockMvc.perform(put("/api/users/me/consents")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"marketingEmails\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.type == 'MARKETING_EMAIL')].granted").value(true));
        mockMvc.perform(put("/api/users/me/consents")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("terms_withdrawal_not_supported"));
    }

    // --- errors --------------------------------------------------------------------------------------

    @Test
    void validationErrorsAreProblemDetailsWithFieldErrors() throws Exception {
        postJson("/api/auth/register", """
            {"firstName":"","lastName":"L","email":"not-an-email","password":"short"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
            .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }
}
