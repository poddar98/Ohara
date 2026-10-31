package org.example.ohara.platform.audit;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.CourseFixtures;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private String orgAudit(Org org) throws Exception {
        return json(fx.send(get("/api/orgs/{id}/audit?size=200", org.id()), org.admin(), null).andExpect(status().isOk()));
    }

    private static List<String> actions(String page) {
        return JsonPath.read(page, "$.items[*].action");
    }

    @Test
    void organizationActionsAreAuditedAndVisibleToItsAdmins() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser member = fx.member(org, "LEARNER");
        fx.send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), member.id()), org.admin(), "{\"roles\":[\"INSTRUCTOR\"]}")
            .andExpect(status().isOk());
        String invitation = json(fx.send(post("/api/orgs/{orgId}/invitations", org.id()), org.admin(),
            "{\"email\":\"invitee-%s@example.com\",\"role\":\"LEARNER\"}".formatted(UUID.randomUUID())).andExpect(status().isCreated()));
        fx.send(post("/api/orgs/{orgId}/invitations/{id}/revoke", org.id(), (String) JsonPath.read(invitation, "$.id")), org.admin(), null)
            .andExpect(status().isOk());

        String page = orgAudit(org);
        assertThat(actions(page)).contains("MEMBER_ADDED", "MEMBER_ROLES_CHANGED", "INVITATION_SENT", "INVITATION_REVOKED");
        assertThat(JsonPath.<List<List<String>>>read(page, "$.items[?(@.action == 'MEMBER_ROLES_CHANGED')].details.fromRoles"))
            .containsExactly(List.of("LEARNER"));
        assertThat(JsonPath.<List<List<String>>>read(page, "$.items[?(@.action == 'MEMBER_ROLES_CHANGED')].details.toRoles"))
            .containsExactly(List.of("INSTRUCTOR"));
        assertThat(JsonPath.<List<String>>read(page, "$.items[?(@.action == 'INVITATION_SENT')].actorUserId"))
            .containsOnly(org.admin().id().toString());
        assertThat(JsonPath.<List<String>>read(page, "$.items[*].ipHash")).allSatisfy(h -> assertThat(h).hasSize(32));
        assertThat(page).doesNotContain("invitee-").doesNotContain("127.0.0.1");

        fx.send(get("/api/orgs/{id}/audit?action=INVITATION_SENT", org.id()), org.admin(), null)
            .andExpect(jsonPath("$.items.length()").value(1));
        fx.send(get("/api/orgs/{id}/audit", org.id()), member, null).andExpect(status().isForbidden());
    }

    @Test
    void crossTenantAttemptsAreRecordedAgainstTheTargetOrganization() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();

        fx.send(get("/api/orgs/{id}/members", org.id()), other.admin(), null).andExpect(status().isNotFound());
        fx.send(get("/api/orgs/{id}/audit", org.id()), other.admin(), null).andExpect(status().isNotFound());

        String page = orgAudit(org);
        assertThat(JsonPath.<List<String>>read(page, "$.items[?(@.action == 'ACCESS_DENIED_CROSS_TENANT')].actorUserId"))
            .hasSize(2).containsOnly(other.admin().id().toString());
        // ...and nothing about it shows in the other organization's trail.
        assertThat(actions(orgAudit(other))).doesNotContain("ACCESS_DENIED_CROSS_TENANT");
    }

    @Test
    void contractGrantsCarryTheirPaymentReference() throws Exception {
        UUID courseId = new CourseFixtures(fx).published(fx.platformAdmin(), null).courseId();
        UUID offeringId = UUID.fromString(JsonPath.read(json(fx.send(post("/api/admin/offerings"), fx.platformAdmin(),
            "{\"courseId\":\"%s\",\"accessDurationDays\":180,\"listPriceMinor\":100000,\"currency\":\"INR\"}".formatted(courseId))), "$.id"));
        fx.send(post("/api/admin/offerings/{id}/open", offeringId), fx.platformAdmin(), null).andExpect(status().isOk());
        TestUser contractTeam = users.withPlatformRole("ROLE_ENROLLMENT_MANAGER");
        String reference = "UTR-" + UUID.randomUUID().toString().substring(0, 8);

        String grant = json(fx.send(post("/api/admin/offerings/{id}/grants", offeringId), contractTeam,
                "{\"email\":\"buyer-%s@example.com\",\"firstName\":\"Neha\",\"lastName\":\"Shah\",\"externalReference\":\"%s\"}"
                    .formatted(UUID.randomUUID(), reference))
            .andExpect(status().isCreated()));
        String grantId = JsonPath.read(grant, "$.grantId");
        fx.send(post("/api/admin/grants/{id}/revoke", grantId), contractTeam, "{\"reason\":\"REFUNDED_OFFLINE\"}").andExpect(status().isOk());

        String page = json(fx.send(get("/api/admin/audit?resourceType=ACCESS_GRANT&resourceId={id}", grantId), fx.platformAdmin(), null)
            .andExpect(status().isOk()));
        assertThat(actions(page)).containsExactly("ACCESS_REVOKED", "ACCESS_GRANTED");
        assertThat(JsonPath.<List<String>>read(page, "$.items[*].details.externalReference")).containsOnly(reference);
        assertThat(JsonPath.<String>read(page, "$.items[0].details.reason")).isEqualTo("REFUNDED_OFFLINE");
        assertThat(JsonPath.<String>read(page, "$.items[1].actorUserId")).isEqualTo(contractTeam.id().toString());

        fx.send(get("/api/admin/audit"), contractTeam, null).andExpect(status().isForbidden());
    }

    @Test
    void lockoutsAndRefreshTokenReuseAreRecorded() throws Exception {
        TestUser user = users.user();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(user.email())))
                .andExpect(status().isUnauthorized());
        }
        String page = json(fx.send(get("/api/admin/audit?action=ACCOUNT_LOCKED&resourceId={id}", user.id()), fx.platformAdmin(), null));
        assertThat(JsonPath.<List<String>>read(page, "$.items[*].actorType")).containsExactly("SYSTEM");
        assertThat(JsonPath.<Integer>read(page, "$.items[0].details.failedAttempts")).isEqualTo(5);

        TestUser other = users.user();
        jakarta.servlet.http.Cookie refresh = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(other.email(), TestUsers.PASSWORD)))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie("refresh_token");
        mockMvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized()); // replayed: stolen?
        assertThat(actions(json(fx.send(get("/api/admin/audit?resourceType=USER&resourceId={id}", other.id()), fx.platformAdmin(), null))))
            .containsExactly("REFRESH_TOKEN_REUSED");
    }

    @Test
    void theTrailIsAppendOnly() {
        jdbc.sql("INSERT INTO audit_event (actor_type, action, resource_type) VALUES ('SYSTEM', 'TEST', 'TEST')").update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET action = 'X' WHERE action = 'TEST'").update())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE action = 'TEST'").update())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_event").update()).hasMessageContaining("append-only");
    }
}
