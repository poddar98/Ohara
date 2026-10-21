package org.example.ohara.organization.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.notification.infrastructure.InMemoryEmailSender;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BulkImportIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;
    @Autowired JobScheduler scheduler;
    @Autowired InMemoryEmailSender emails;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    private static String email() {
        return "import-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions upload(Org org, String csv, String mode, UUID cohortId) throws Exception {
        var request = multipart("/api/orgs/{orgId}/imports", org.id())
            .file(new MockMultipartFile("file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .param("mode", mode)
            .header("Authorization", org.admin().bearer());
        if (cohortId != null) {
            request.param("cohortId", cohortId.toString());
        }
        return mockMvc.perform(request);
    }

    private String uploadedId(ResultActions upload) throws Exception {
        return JsonPath.read(upload.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions confirmAndRun(Org org, String importId) throws Exception {
        fx.send(post("/api/orgs/{orgId}/imports/{id}/confirm", org.id(), importId), org.admin(), null)
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"));
        jobs.drain();
        return fx.send(get("/api/orgs/{orgId}/imports/{id}", org.id(), importId), org.admin(), null);
    }

    @Test
    void uploadValidatesEveryRowAndAppliesNothing() throws Exception {
        Org org = fx.orgWithAdmin();
        String a = email();
        String csv = "Email,First Name,Last Name,Roll Number\n"
            + a + ",Asha,Rao,R1\n"
            + "not-an-email,Bad,Row,R2\n"
            + a.toUpperCase() + ",Dup,Row,R3\n"
            + email() + ",Ravi,Kumar,R1\n";

        String id = uploadedId(upload(org, csv, "INVITE", null)
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.totalRows").value(4))
            .andExpect(jsonPath("$.validRows").value(1)));

        fx.send(get("/api/orgs/{orgId}/imports/{id}/rows", org.id(), id).param("status", "INVALID"), org.admin(), null)
            .andExpect(jsonPath("$.items", hasSize(3)))
            .andExpect(jsonPath("$.items[0].errorCode").value("INVALID_EMAIL"))
            .andExpect(jsonPath("$.items[1].errorCode").value("DUPLICATE_IN_FILE"))
            .andExpect(jsonPath("$.items[2].errorCode").value("DUPLICATE_EXTERNAL_REF"));
        int invitations = jdbc.sql("SELECT count(*) FROM invitation WHERE organization_id = :org")
            .param("org", org.id()).query(Integer.class).single();
        assertThat(invitations).isZero();
    }

    @Test
    void inviteModeInvitesNewPeopleAndSkipsExistingMembers() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID cohortId = fx.cohort(org, "B1");
        TestUser alreadyPlaced = fx.member(org, "LEARNER");
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(alreadyPlaced.id())).andExpect(status().isOk());
        String newPerson = email();
        String csv = "email,first_name,last_name\n" + newPerson + ",Asha,Rao\n" + alreadyPlaced.email() + ",Old,Member\nbroken,,\n";

        String id = uploadedId(upload(org, csv, "INVITE", cohortId));
        confirmAndRun(org, id)
            .andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
            .andExpect(jsonPath("$.succeededRows").value(1))
            .andExpect(jsonPath("$.skippedRows").value(1))
            .andExpect(jsonPath("$.failedRows").value(1));

        assertThat(emails.sentTo(newPerson)).anySatisfy(m -> assertThat(m.subject()).contains("invited"));
        fx.send(get("/api/orgs/{orgId}/invitations", org.id()).param("status", "PENDING"), org.admin(), null)
            .andExpect(jsonPath("$.items[0].email").value(newPerson))
            .andExpect(jsonPath("$.items[0].cohortId").value(cohortId.toString()));
        fx.send(get("/api/orgs/{orgId}/imports/{id}/errors.csv", org.id(), id), org.admin(), null)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string(containsString("broken,,,,INVALID_EMAIL")));
    }

    @Test
    void createAccountsModeProvisionsAccountsThatActivateWhenThePasswordIsSet() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID cohortId = fx.cohort(org, "B2");
        TestUser existing = users.user();
        String fresh = email();
        String csv = "email,first_name,last_name,external_ref\n" + fresh + ",Meera,Iyer,CS-001\n"
            + existing.email() + ",Existing,Person,CS-002\n";

        String id = uploadedId(upload(org, csv, "CREATE_ACCOUNTS", cohortId));
        confirmAndRun(org, id)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.succeededRows").value(2));
        fx.send(get("/api/orgs/{orgId}/imports/{id}/rows", org.id(), id), org.admin(), null)
            .andExpect(jsonPath("$.items[0].status").value("CREATED"))
            .andExpect(jsonPath("$.items[1].status").value("LINKED_EXISTING"));

        fx.send(get("/api/orgs/{orgId}/cohorts/{cohortId}", org.id(), cohortId), org.admin(), null)
            .andExpect(jsonPath("$.activeMembers").value(2));
        fx.send(get("/api/orgs/{orgId}/members", org.id()).param("role", "LEARNER"), org.admin(), null)
            .andExpect(jsonPath("$.items[?(@.externalRef == 'CS-001')]").exists());

        // The provisioned account has no password until the emailed link is used, which also verifies it.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"anything-123\"}".formatted(fresh)))
            .andExpect(status().isUnauthorized());
        String body = emails.sentTo(fresh).stream().filter(m -> m.subject().contains("account is ready"))
            .findFirst().orElseThrow().body();
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"my-new-pass\"}".formatted(matcher.group(1))))
            .andExpect(status().isNoContent());
        String token = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"my-new-pass\"}".formatted(fresh)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/users/me/organizations").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$[0].cohorts[0].id").value(cohortId.toString()));
        // No second account for the existing person.
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = lower(:e)").param("e", existing.email())
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void reprocessingAfterACrashNeverDuplicates() throws Exception {
        Org org = fx.orgWithAdmin();
        String person = email();
        String id = uploadedId(upload(org, "email,first_name,last_name\n" + person + ",A,B\n", "CREATE_ACCOUNTS", null));
        confirmAndRun(org, id).andExpect(jsonPath("$.status").value("COMPLETED"));

        // Simulate a crash after the row was applied but before it was recorded, then the job running again.
        jdbc.sql("UPDATE bulk_import_row SET status = 'PENDING' WHERE job_id = :id").param("id", UUID.fromString(id)).update();
        jdbc.sql("UPDATE bulk_import_job SET status = 'PROCESSING' WHERE id = :id").param("id", UUID.fromString(id)).update();
        scheduler.enqueue(JobRequest.of("BULK_IMPORT", "BULK_IMPORT:rerun:" + UUID.randomUUID(), Map.of("importId", id)));
        jobs.drain();

        fx.send(get("/api/orgs/{orgId}/imports/{id}/rows", org.id(), id), org.admin(), null)
            .andExpect(jsonPath("$.items[0].status").value("SKIPPED_DUPLICATE"));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", person).query(Integer.class).single())
            .isEqualTo(1);
    }

    @Test
    void sameFileCantBeImportedTwiceUnlessTheFirstWasCancelled() throws Exception {
        Org org = fx.orgWithAdmin();
        String csv = "email\n" + email() + "\n";
        String first = uploadedId(upload(org, csv, "INVITE", null));

        upload(org, csv, "INVITE", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_file"));
        fx.send(post("/api/orgs/{orgId}/imports/{id}/cancel", org.id(), first), org.admin(), null)
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        fx.send(post("/api/orgs/{orgId}/imports/{id}/confirm", org.id(), first), org.admin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("import_not_confirmable"));
        upload(org, csv, "INVITE", null).andExpect(status().isCreated());
    }

    @Test
    void badFilesAreRejectedUpFront() throws Exception {
        Org org = fx.orgWithAdmin();

        upload(org, "name,phone\nAsha,123\n", "INVITE", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("missing_email_column"));
        upload(org, "email\n\"unterminated@example.com\n", "INVITE", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("malformed_csv"));
        upload(org, "email\n", "INVITE", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("no_rows"));
        upload(org, "email\n" + email() + "\n", "CREATE_ACCOUNTS", null)
            .andExpect(status().isCreated()).andExpect(jsonPath("$.validRows").value(0));
    }

    @Test
    void importsAreTenantScopedAndAdminOnly() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        TestUser learner = fx.member(org, "LEARNER");
        String id = uploadedId(upload(org, "email\n" + email() + "\n", "INVITE", null));

        fx.send(get("/api/orgs/{orgId}/imports/{id}", other.id(), id), other.admin(), null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("import_not_found"));
        fx.send(get("/api/orgs/{orgId}/imports", org.id()), learner, null).andExpect(status().isForbidden());
    }
}
