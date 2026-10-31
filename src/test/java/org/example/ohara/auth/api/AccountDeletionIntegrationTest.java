package org.example.ohara.auth.api;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountDeletionIntegrationTest {

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

    private ResultActions requestDeletion(TestUser as, String password) throws Exception {
        return fx.send(post("/api/users/me/deletion-request"), as, "{\"password\":\"%s\"}".formatted(password));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestUsers.PASSWORD)));
    }

    /** The grace period, skipped: the scheduled anonymization becomes due now. */
    private void runDueDeletions(UUID userId) {
        jdbc.sql("UPDATE processing_job SET run_after = now() WHERE job_type = 'ACCOUNT_ANONYMIZE' AND status = 'PENDING' AND payload->>'userId' = :u")
            .param("u", userId.toString()).update();
        jobs.drain();
    }

    private Map<String, Object> userRow(UUID userId) {
        return jdbc.sql("SELECT * FROM users WHERE id = :u").param("u", userId).query().singleRow();
    }

    private long count(String sql, UUID userId) {
        return jdbc.sql(sql).param("u", userId).query(Long.class).single();
    }

    private List<String> auditActions(UUID userId) {
        return jdbc.sql("SELECT action FROM audit_event WHERE resource_type = 'USER' AND resource_id = :u ORDER BY id")
            .param("u", userId.toString()).query(String.class).list();
    }

    @Test
    void aDeletedAccountIsClosedAtOnceAndAnonymizedAfterTheGracePeriod() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser learner = users.user();
        fx.send(post("/api/orgs/{orgId}/members", org.id()), org.admin(),
            "{\"email\":\"%s\",\"roles\":[\"LEARNER\"],\"externalRef\":\"ROLL-%s\"}".formatted(learner.email(), learner.id().toString().substring(0, 6)))
            .andExpect(status().isCreated());
        UUID cohortId = fx.cohort(org, "D" + UUID.randomUUID().toString().substring(0, 4));
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());
        Org other = fx.orgWithAdmin();
        fx.send(post("/api/orgs/{orgId}/invitations", other.id()), other.admin(),
            "{\"email\":\"%s\",\"role\":\"LEARNER\"}".formatted(learner.email())).andExpect(status().isCreated());

        requestDeletion(learner, "not-my-password").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("password_incorrect"));
        String body = requestDeletion(learner, TestUsers.PASSWORD).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("DEACTIVATED")).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.deletionDueAt")))
            .isBetween(Instant.now().plus(29, ChronoUnit.DAYS), Instant.now().plus(31, ChronoUnit.DAYS));
        jobs.drain(); // email delivery (the anonymization itself isn't due for 30 days)
        assertThat(userRow(learner.id()).get("status")).isEqualTo("DEACTIVATED");
        assertThat(emails.sentTo(learner.email())).extracting(EmailMessage::subject).contains("Your Ohara account will be deleted");

        // Closed now: the session stops working and sign-in is refused.
        fx.send(get("/api/users/me"), learner, null).andExpect(status().isUnauthorized());
        login(learner.email()).andExpect(status().isUnauthorized());
        assertThat(userRow(learner.id()).get("email")).isEqualTo(learner.email()); // not yet anonymized

        runDueDeletions(learner.id());

        Map<String, Object> row = userRow(learner.id());
        assertThat(row.get("status")).isEqualTo("ANONYMIZED");
        assertThat(row.get("email")).isEqualTo("deleted+" + learner.id() + "@invalid");
        assertThat(row.get("first_name")).isEqualTo("Deleted");
        assertThat(count("SELECT count(*) FROM auth_identity WHERE user_id = :u", learner.id())).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = :u", learner.id())).isZero();
        assertThat(count("SELECT count(*) FROM user_consent WHERE user_id = :u AND granted = false AND consent_type = 'TERMS'", learner.id()))
            .isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(external_ref, '-') FROM organization_membership WHERE user_id = :u")
            .param("u", learner.id()).query(String.class).single()).isEqualTo("REMOVED:-");
        assertThat(count("SELECT count(*) FROM cohort_membership WHERE user_id = :u AND left_at IS NULL", learner.id())).isZero();
        assertThat(jdbc.sql("SELECT status FROM invitation WHERE organization_id = :o").param("o", other.id()).query(String.class).single())
            .isEqualTo("REVOKED");
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE lower(email) = lower(:e)").param("e", learner.email())
            .query(Long.class).single()).isZero();
        assertThat(auditActions(learner.id())).containsSubsequence("ACCOUNT_DELETION_REQUESTED", "ACCOUNT_ANONYMIZED");

        // The address is free again: the person can start over with a new account.
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                "{\"firstName\":\"New\",\"lastName\":\"Start\",\"email\":\"%s\",\"password\":\"another-secret-1\",\"acceptTerms\":true}"
                    .formatted(learner.email())))
            .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = lower(:e)").param("e", learner.email())
            .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aLegalHoldDefersAnonymizationUntilReleased() throws Exception {
        TestUser buyer = users.user();
        TestUser contractTeam = users.withPlatformRole("ROLE_ENROLLMENT_MANAGER");
        requestDeletion(buyer, TestUsers.PASSWORD).andExpect(status().isAccepted());

        fx.send(put("/api/admin/users/{id}/legal-hold", buyer.id()), buyer, "{\"reason\":\"x\"}").andExpect(status().isUnauthorized());
        fx.send(put("/api/admin/users/{id}/legal-hold", buyer.id()), contractTeam, "{\"reason\":\"Refund UTR-77 disputed\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.onLegalHold").value(true));

        runDueDeletions(buyer.id());
        assertThat(userRow(buyer.id()).get("status")).isEqualTo("DEACTIVATED");
        Instant newDue = ((java.sql.Timestamp) userRow(buyer.id()).get("deletion_due_at")).toInstant();
        assertThat(newDue).isBetween(Instant.now().plus(6, ChronoUnit.DAYS), Instant.now().plus(8, ChronoUnit.DAYS));
        assertThat(emails.sentTo(buyer.email())).extracting(EmailMessage::subject).contains("Your Ohara account deletion is on hold");

        fx.send(delete("/api/admin/users/{id}/legal-hold", buyer.id()), contractTeam, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.onLegalHold").value(false));
        runDueDeletions(buyer.id());
        assertThat(userRow(buyer.id()).get("status")).isEqualTo("ANONYMIZED");
        assertThat(auditActions(buyer.id())).containsSubsequence("ACCOUNT_DELETION_REQUESTED", "LEGAL_HOLD_PLACED",
            "ACCOUNT_DELETION_DEFERRED", "LEGAL_HOLD_RELEASED", "ACCOUNT_ANONYMIZED");
    }

    @Test
    void supportCanCancelAPendingDeletion() throws Exception {
        TestUser person = users.user();
        requestDeletion(person, TestUsers.PASSWORD).andExpect(status().isAccepted());

        fx.send(post("/api/admin/users/{id}/deletion/cancel", person.id()), users.withPlatformRole("ROLE_PLATFORM_SUPPORT"), null)
            .andExpect(status().isForbidden());
        fx.send(post("/api/admin/users/{id}/deletion/cancel", person.id()), fx.platformAdmin(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.deletionDueAt").doesNotExist());
        login(person.email()).andExpect(status().isOk());

        runDueDeletions(person.id()); // the old job finds the deletion cancelled
        assertThat(userRow(person.id()).get("status")).isEqualTo("ACTIVE");
        fx.send(post("/api/admin/users/{id}/deletion/cancel", person.id()), fx.platformAdmin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("no_deletion_pending"));
    }
}
