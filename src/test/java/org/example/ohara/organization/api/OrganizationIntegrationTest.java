package org.example.ohara.organization.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrganizationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    TestUsers users;
    TestUser platformAdmin;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        platformAdmin = users.platformAdmin();
    }

    // --- helpers -------------------------------------------------------------------------------------

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               TestUser as, String json) throws Exception {
        request.header("Authorization", as.bearer());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private UUID createOrg(String name) throws Exception {
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 12);
        String body = send(post("/api/admin/organizations"), platformAdmin,
                "{\"name\":\"%s\",\"slug\":\"%s\",\"type\":\"COLLEGE\"}".formatted(name, slug))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions addMember(UUID orgId, TestUser as, TestUser member, String... roles) throws Exception {
        String rolesJson = String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
        return send(post("/api/orgs/{orgId}/members", orgId), as,
            "{\"email\":\"%s\",\"roles\":[%s]}".formatted(member.email(), rolesJson));
    }

    /** An organization with one admin (added by the platform admin). */
    private record Org(UUID id, TestUser admin) {
    }

    private Org orgWithAdmin() throws Exception {
        UUID orgId = createOrg("College " + UUID.randomUUID());
        TestUser admin = users.user();
        addMember(orgId, platformAdmin, admin, "ORG_ADMIN").andExpect(status().isCreated());
        return new Org(orgId, admin);
    }

    private UUID createCohort(Org org, String code) throws Exception {
        String body = send(post("/api/orgs/{orgId}/cohorts", org.id()), org.admin(),
                "{\"name\":\"Batch %s\",\"code\":\"%s\",\"startsOn\":\"2026-07-01\",\"endsOn\":\"2027-06-30\"}".formatted(code, code))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private TestUser learnerIn(Org org) throws Exception {
        TestUser learner = users.user();
        addMember(org.id(), org.admin(), learner, "LEARNER").andExpect(status().isCreated());
        return learner;
    }

    // --- organizations -------------------------------------------------------------------------------

    @Test
    void onlyPlatformAdminsCreateOrganizations() throws Exception {
        TestUser regular = users.user();
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 12);
        String json = "{\"name\":\"X\",\"slug\":\"%s\",\"type\":\"COLLEGE\"}".formatted(slug);

        send(post("/api/admin/organizations"), regular, json).andExpect(status().isForbidden());
        send(post("/api/admin/organizations"), platformAdmin, json).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
        send(post("/api/admin/organizations"), platformAdmin, json).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("slug_taken"));
        send(post("/api/admin/organizations"), platformAdmin, "{\"name\":\"X\",\"slug\":\"Bad Slug!\",\"type\":\"COLLEGE\"}")
            .andExpect(status().isBadRequest());
    }

    @Test
    void myOrganizationsListsActiveAffiliationsWithRolesAndCohorts() throws Exception {
        Org org = orgWithAdmin();
        TestUser learner = learnerIn(org);
        UUID cohortId = createCohort(org, "B1");
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());

        send(get("/api/users/me/organizations"), learner, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].organization.id").value(org.id().toString()))
            .andExpect(jsonPath("$[0].roles[0]").value("LEARNER"))
            .andExpect(jsonPath("$[0].cohorts[0].id").value(cohortId.toString()));
    }

    @Test
    void suspendedOrganizationBlocksMembersButNotPlatformAdmins() throws Exception {
        Org org = orgWithAdmin();
        TestUser learner = learnerIn(org);

        send(put("/api/admin/organizations/{orgId}/status", org.id()), platformAdmin, "{\"status\":\"SUSPENDED\"}")
            .andExpect(status().isOk());

        send(get("/api/orgs/{orgId}", org.id()), org.admin(), null)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("organization_inactive"));
        send(get("/api/orgs/{orgId}", org.id()), platformAdmin, null).andExpect(status().isOk());
        send(get("/api/users/me/organizations"), learner, null).andExpect(jsonPath("$", hasSize(0)));
    }

    // --- members and roles ---------------------------------------------------------------------------

    @Test
    void addingMembersMergesRolesAndRequiresAnAccount() throws Exception {
        Org org = orgWithAdmin();
        TestUser person = users.user();

        addMember(org.id(), org.admin(), person, "LEARNER").andExpect(status().isCreated());
        addMember(org.id(), org.admin(), person, "INSTRUCTOR")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("INSTRUCTOR", "LEARNER")));

        send(post("/api/orgs/{orgId}/members", org.id()), org.admin(),
                "{\"email\":\"nobody-%s@example.com\",\"roles\":[\"LEARNER\"]}".formatted(UUID.randomUUID()))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("user_not_registered"));
    }

    @Test
    void membersCanBeFilteredAndPaged() throws Exception {
        Org org = orgWithAdmin();
        for (int i = 0; i < 3; i++) {
            learnerIn(org);
        }

        send(get("/api/orgs/{orgId}/members", org.id()).param("role", "LEARNER").param("size", "2"), org.admin(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.totalPages").value(2));
        send(get("/api/orgs/{orgId}/members", org.id()), org.admin(), null)
            .andExpect(jsonPath("$.totalItems").value(4));
    }

    @Test
    void anOrganizationAlwaysKeepsAnActiveAdmin() throws Exception {
        Org org = orgWithAdmin();

        send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), org.admin().id()), org.admin(), "{\"roles\":[\"LEARNER\"]}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_org_admin"));
        send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), org.admin().id()), org.admin(), "{\"status\":\"REMOVED\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_org_admin"));

        TestUser second = users.user();
        addMember(org.id(), org.admin(), second, "ORG_ADMIN").andExpect(status().isCreated());
        send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), org.admin().id()), org.admin(), "{\"status\":\"REMOVED\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"));

        // The removed admin is out immediately; the org stays manageable by the second admin.
        send(get("/api/orgs/{orgId}/members", org.id()), org.admin(), null).andExpect(status().isNotFound());
        send(get("/api/orgs/{orgId}/members", org.id()), second, null).andExpect(status().isOk());
    }

    @Test
    void rolesDecideWhatMembersMayDo() throws Exception {
        Org org = orgWithAdmin();
        TestUser learner = learnerIn(org);
        TestUser instructor = users.user();
        addMember(org.id(), org.admin(), instructor, "INSTRUCTOR").andExpect(status().isCreated());

        send(get("/api/orgs/{orgId}", org.id()), learner, null).andExpect(status().isOk());
        send(get("/api/orgs/{orgId}/members", org.id()), learner, null).andExpect(status().isForbidden());
        send(get("/api/orgs/{orgId}/cohorts", org.id()), learner, null).andExpect(status().isForbidden());

        send(get("/api/orgs/{orgId}/cohorts", org.id()), instructor, null).andExpect(status().isOk());
        send(post("/api/orgs/{orgId}/cohorts", org.id()), instructor, "{\"name\":\"X\",\"code\":\"X1\"}")
            .andExpect(status().isForbidden());
        addMember(org.id(), instructor, users.user(), "LEARNER").andExpect(status().isForbidden());
    }

    // --- cohorts -------------------------------------------------------------------------------------

    @Test
    void cohortCodesAreUniqueAndDatesValidated() throws Exception {
        Org org = orgWithAdmin();
        createCohort(org, "CSE-2026");

        send(post("/api/orgs/{orgId}/cohorts", org.id()), org.admin(), "{\"name\":\"Dup\",\"code\":\"CSE-2026\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cohort_code_taken"));
        send(post("/api/orgs/{orgId}/cohorts", org.id()), org.admin(),
                "{\"name\":\"Bad\",\"code\":\"BAD\",\"startsOn\":\"2026-07-01\",\"endsOn\":\"2026-01-01\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_dates"));
    }

    @Test
    void onlyActiveLearnersArePlacedAndAddingIsIdempotent() throws Exception {
        Org org = orgWithAdmin();
        UUID cohortId = createCohort(org, "B1");
        TestUser learner = learnerIn(org);
        TestUser instructorOnly = users.user();
        addMember(org.id(), org.admin(), instructorOnly, "INSTRUCTOR").andExpect(status().isCreated());
        TestUser outsider = users.user();

        String json = "{\"userIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(learner.id(), instructorOnly.id(), outsider.id());
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(), json)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.added", containsInAnyOrder(learner.id().toString())))
            .andExpect(jsonPath("$.notEligible", containsInAnyOrder(instructorOnly.id().toString(), outsider.id().toString())));
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(), json)
            .andExpect(jsonPath("$.added", hasSize(0)))
            .andExpect(jsonPath("$.alreadyMember", containsInAnyOrder(learner.id().toString())));

        send(get("/api/orgs/{orgId}/cohorts/{cohortId}", org.id(), cohortId), org.admin(), null)
            .andExpect(jsonPath("$.activeMembers").value(1));
    }

    @Test
    void removalAndTransferKeepHistory() throws Exception {
        Org org = orgWithAdmin();
        UUID a = createCohort(org, "A");
        UUID b = createCohort(org, "B");
        TestUser learner = learnerIn(org);
        TestUser other = learnerIn(org);
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), a), org.admin(),
            "{\"userIds\":[\"%s\",\"%s\"]}".formatted(learner.id(), other.id())).andExpect(status().isOk());

        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}/transfer", org.id(), a, learner.id()), org.admin(),
                "{\"toCohortId\":\"%s\"}".formatted(b))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", org.id(), a, other.id()), org.admin(), null)
            .andExpect(status().isNoContent());
        send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", org.id(), a, other.id()), org.admin(), null)
            .andExpect(status().isNotFound());

        send(get("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), a), org.admin(), null)
            .andExpect(jsonPath("$", hasSize(0)));
        send(get("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), a).param("includeFormer", "true"), org.admin(), null)
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[*].status", containsInAnyOrder("TRANSFERRED", "REMOVED")));
        send(get("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), b), org.admin(), null)
            .andExpect(jsonPath("$[0].userId").value(learner.id().toString()));
    }

    @Test
    void completedCohortsCloseOpenPlacementsAndRejectNewOnes() throws Exception {
        Org org = orgWithAdmin();
        UUID cohortId = createCohort(org, "OLD");
        TestUser learner = learnerIn(org);
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());

        send(patch("/api/orgs/{orgId}/cohorts/{cohortId}", org.id(), cohortId), org.admin(), "{\"status\":\"COMPLETED\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.activeMembers").value(0));
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
                "{\"userIds\":[\"%s\"]}".formatted(learner.id()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cohort_closed"));
    }

    @Test
    void removingAMemberEndsTheirCohortPlacements() throws Exception {
        Org org = orgWithAdmin();
        UUID cohortId = createCohort(org, "B1");
        TestUser learner = learnerIn(org);
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());

        send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), learner.id()), org.admin(), "{\"status\":\"REMOVED\"}")
            .andExpect(status().isOk());

        send(get("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId).param("includeFormer", "true"), org.admin(), null)
            .andExpect(jsonPath("$[0].status").value("REMOVED"));
        send(get("/api/orgs/{orgId}", org.id()), learner, null).andExpect(status().isNotFound());
    }

    // --- tenant isolation ----------------------------------------------------------------------------

    @Test
    void anotherOrganizationsDataIsInvisible() throws Exception {
        Org mine = orgWithAdmin();
        Org theirs = orgWithAdmin();
        UUID theirCohort = createCohort(theirs, "THEIRS");
        TestUser theirLearner = learnerIn(theirs);

        send(get("/api/orgs/{orgId}", theirs.id()), mine.admin(), null).andExpect(status().isNotFound());
        send(get("/api/orgs/{orgId}/members", theirs.id()), mine.admin(), null).andExpect(status().isNotFound());
        send(get("/api/orgs/{orgId}/cohorts", theirs.id()), mine.admin(), null).andExpect(status().isNotFound());
        // Their cohort id under my organization's path is not found either.
        send(get("/api/orgs/{orgId}/cohorts/{cohortId}", mine.id(), theirCohort), mine.admin(), null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("cohort_not_found"));
        // Their learner can't be placed in my cohort.
        UUID myCohort = createCohort(mine, "MINE");
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", mine.id(), myCohort), mine.admin(),
                "{\"userIds\":[\"%s\"]}".formatted(theirLearner.id()))
            .andExpect(jsonPath("$.notEligible[0]").value(theirLearner.id().toString()));
        // Nor transferred into their cohort from mine.
        TestUser myLearner = learnerIn(mine);
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", mine.id(), myCohort), mine.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(myLearner.id())).andExpect(status().isOk());
        send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}/transfer", mine.id(), myCohort, myLearner.id()),
                mine.admin(), "{\"toCohortId\":\"%s\"}".formatted(theirCohort))
            .andExpect(status().isNotFound());
        send(patch("/api/orgs/{orgId}/members/{userId}", mine.id(), theirs.admin().id()), mine.admin(), "{\"status\":\"REMOVED\"}")
            .andExpect(status().isNotFound());
    }

    @Test
    void databaseRejectsACohortPlacementInAnotherOrganization() throws Exception {
        Org mine = orgWithAdmin();
        Org theirs = orgWithAdmin();
        UUID theirCohort = createCohort(theirs, "X");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO cohort_membership (id, cohort_id, organization_id, user_id)
                VALUES (:id, :cohort, :org, :user)
                """)
            .param("id", UUID.randomUUID()).param("cohort", theirCohort).param("org", mine.id()).param("user", mine.admin().id())
            .update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
