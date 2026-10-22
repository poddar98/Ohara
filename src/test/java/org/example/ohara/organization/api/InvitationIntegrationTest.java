package org.example.ohara.organization.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.infrastructure.InMemoryEmailSender;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;
    @Autowired InMemoryEmailSender emails;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    private static String newEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions invite(Org org, String email, String role, UUID cohortId) throws Exception {
        String cohort = cohortId == null ? "" : ",\"cohortId\":\"%s\"".formatted(cohortId);
        return fx.send(post("/api/orgs/{orgId}/invitations", org.id()), org.admin(),
            "{\"email\":\"%s\",\"role\":\"%s\"%s}".formatted(email, role, cohort));
    }

    /** Delivers queued email and returns the token of the latest invitation sent to {@code email}. */
    private String invitationToken(String email) {
        jobs.drain();
        EmailMessage message = emails.sentTo(email).stream()
            .filter(m -> m.subject().contains("invited"))
            .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no invitation email to " + email));
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions acceptAnonymously(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/invitations/{token}/accept", token)
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions acceptAs(String token, TestUser user) throws Exception {
        return mockMvc.perform(post("/api/invitations/{token}/accept", token).header("Authorization", user.bearer()));
    }

    // --- new people ----------------------------------------------------------------------------------

    @Test
    void newPersonSignsUpThroughTheInvitationAndLandsInTheCohort() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID cohortId = fx.cohort(org, "B1");
        String email = newEmail();

        invite(org, email, "LEARNER", cohortId).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"));
        String token = invitationToken(email);

        mockMvc.perform(get("/api/invitations/{token}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountExists").value(false))
            .andExpect(jsonPath("$.role").value("LEARNER"))
            .andExpect(jsonPath("$.cohortName").value("Batch B1"));

        MvcResult accepted = acceptAnonymously(token, "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"password\":\"secret1234\",\"acceptTerms\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationId").value(org.id().toString()))
            .andExpect(jsonPath("$.roles[0]").value("LEARNER"))
            .andExpect(jsonPath("$.placedInCohort").value(true))
            .andReturn();
        Cookie refresh = accepted.getResponse().getCookie("refresh_token");
        assertThat(refresh).isNotNull();
        String accessToken = JsonPath.read(accepted.getResponse().getContentAsString(), "$.accessToken");

        // The link proved the inbox: the new account is active and verified, with full access.
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.emailVerified").value(true));
        mockMvc.perform(get("/api/users/me/organizations").header("Authorization", "Bearer " + accessToken))
            .andExpect(jsonPath("$[0].cohorts[0].id").value(cohortId.toString()));

        // A used link can't be used again.
        acceptAnonymously(token, "{\"firstName\":\"X\",\"lastName\":\"Y\",\"password\":\"secret1234\",\"acceptTerms\":true}")
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("invitation_already_used"));
    }

    @Test
    void signingUpThroughAnInvitationRequiresNamesAndPassword() throws Exception {
        Org org = fx.orgWithAdmin();
        String email = newEmail();
        invite(org, email, "LEARNER", null).andExpect(status().isCreated());
        String token = invitationToken(email);

        acceptAnonymously(token, "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"acceptTerms\":true}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("missing_password"));
        acceptAnonymously(token, "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"password\":\"short\",\"acceptTerms\":true}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void signingUpThroughAnInvitationRequiresAcceptingTheTerms() throws Exception {
        Org org = fx.orgWithAdmin();
        String email = newEmail();
        invite(org, email, "LEARNER", null).andExpect(status().isCreated());

        acceptAnonymously(invitationToken(email), "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"password\":\"secret1234\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("terms_not_accepted"));
    }

    // --- existing accounts ---------------------------------------------------------------------------

    @Test
    void existingAccountMustSignInWithTheInvitedEmailToAccept() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser existing = users.user();
        TestUser someoneElse = users.user();
        invite(org, existing.email(), "INSTRUCTOR", null).andExpect(status().isCreated());
        String token = invitationToken(existing.email());

        mockMvc.perform(get("/api/invitations/{token}", token)).andExpect(jsonPath("$.accountExists").value(true));
        acceptAnonymously(token, "{\"firstName\":\"A\",\"lastName\":\"B\",\"password\":\"secret1234\",\"acceptTerms\":true}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("account_exists"));
        acceptAs(token, someoneElse)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invitation_email_mismatch"));

        acceptAs(token, existing)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles[0]").value("INSTRUCTOR"))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
        // Clicking again is harmless.
        acceptAs(token, existing).andExpect(status().isOk());

        int accounts = jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = lower(:e)")
            .param("e", existing.email()).query(Integer.class).single();
        assertThat(accounts).isEqualTo(1);
    }

    @Test
    void acceptingAddsTheRoleToAnExistingMembership() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser learner = fx.member(org, "LEARNER");
        invite(org, learner.email(), "INSTRUCTOR", null).andExpect(status().isCreated());

        acceptAs(invitationToken(learner.email()), learner)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("INSTRUCTOR", "LEARNER")));
    }

    @Test
    void invitingSomeoneWhoAlreadyHasTheRoleIsAConflict() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser learner = fx.member(org, "LEARNER");

        invite(org, learner.email(), "LEARNER", null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_member"));
    }

    @Test
    void unverifiedAccountIsVerifiedByAcceptingAndGetsAFullSession() throws Exception {
        Org org = fx.orgWithAdmin();
        String email = newEmail();
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"P\",\"lastName\":\"Q\",\"email\":\"%s\",\"password\":\"secret1234\",\"acceptTerms\":true}".formatted(email)))
            .andExpect(status().isAccepted());
        String limited = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"secret1234\"}".formatted(email)))
            .andReturn().getResponse().getContentAsString(), "$.accessToken");
        invite(org, email, "LEARNER", null).andExpect(status().isCreated());

        MvcResult accepted = mockMvc.perform(post("/api/invitations/{token}/accept", invitationToken(email))
                .header("Authorization", "Bearer " + limited))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andReturn();
        String full = JsonPath.read(accepted.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/users/me/organizations").header("Authorization", "Bearer " + full))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    // --- lifecycle -----------------------------------------------------------------------------------

    @Test
    void reInvitingRotatesTheLinkAndKeepsOneOpenInvitation() throws Exception {
        Org org = fx.orgWithAdmin();
        String email = newEmail();
        invite(org, email, "LEARNER", null).andExpect(status().isCreated());
        String first = invitationToken(email);

        invite(org, email, "INSTRUCTOR", null).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("INSTRUCTOR"));
        String second = invitationToken(email);

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/api/invitations/{token}", first)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invitations/{token}", second)).andExpect(jsonPath("$.role").value("INSTRUCTOR"));
        fx.send(get("/api/orgs/{orgId}/invitations", org.id()).param("status", "PENDING"), org.admin(), null)
            .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void revokedAndExpiredInvitationsCantBeUsedButExpiredOnesCanBeResent() throws Exception {
        Org org = fx.orgWithAdmin();
        String revokedEmail = newEmail();
        String id = JsonPath.read(invite(org, revokedEmail, "LEARNER", null).andReturn().getResponse().getContentAsString(), "$.id");
        String revokedToken = invitationToken(revokedEmail);
        fx.send(post("/api/orgs/{orgId}/invitations/{id}/revoke", org.id(), id), org.admin(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(get("/api/invitations/{token}", revokedToken))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("invitation_revoked"));

        String expiringEmail = newEmail();
        String expiringId = JsonPath.read(invite(org, expiringEmail, "LEARNER", null).andReturn().getResponse().getContentAsString(), "$.id");
        String expiredToken = invitationToken(expiringEmail);
        jdbc.sql("UPDATE invitation SET expires_at = now() - interval '1 minute', last_sent_at = now() - interval '1 day' WHERE id = :id")
            .param("id", UUID.fromString(expiringId)).update();

        mockMvc.perform(get("/api/invitations/{token}", expiredToken))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("invitation_expired"));
        fx.send(post("/api/orgs/{orgId}/invitations/{id}/resend", org.id(), expiringId), org.admin(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.sendCount").value(2));
        mockMvc.perform(get("/api/invitations/{token}", invitationToken(expiringEmail))).andExpect(status().isOk());
    }

    @Test
    void resendingTooSoonIsRateLimited() throws Exception {
        Org org = fx.orgWithAdmin();
        String id = JsonPath.read(invite(org, newEmail(), "LEARNER", null).andReturn().getResponse().getContentAsString(), "$.id");

        fx.send(post("/api/orgs/{orgId}/invitations/{id}/resend", org.id(), id), org.admin(), null)
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("resend_too_soon"));
    }

    @Test
    void cohortInvitationsAreForLearnersOnly() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID cohortId = fx.cohort(org, "B1");

        invite(org, newEmail(), "INSTRUCTOR", cohortId)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("cohort_requires_learner"));
    }

    @Test
    void unknownTokenIsNotFound() throws Exception {
        mockMvc.perform(get("/api/invitations/{token}", "definitely-not-a-token"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("invitation_not_found"));
    }

    // --- authorization -------------------------------------------------------------------------------

    @Test
    void onlyAdminsOfTheOrganizationManageItsInvitations() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        TestUser instructor = fx.member(org, "INSTRUCTOR");
        String id = JsonPath.read(invite(org, newEmail(), "LEARNER", null).andReturn().getResponse().getContentAsString(), "$.id");

        fx.send(post("/api/orgs/{orgId}/invitations", org.id()), instructor,
                "{\"email\":\"%s\",\"role\":\"LEARNER\"}".formatted(newEmail()))
            .andExpect(status().isForbidden());
        fx.send(get("/api/orgs/{orgId}/invitations", org.id()), other.admin(), null).andExpect(status().isNotFound());
        fx.send(post("/api/orgs/{orgId}/invitations/{id}/revoke", other.id(), id), other.admin(), null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("invitation_not_found"));
    }
}
