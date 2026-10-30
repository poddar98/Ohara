package org.example.ohara.enrollment.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.notification.infrastructure.InMemoryEmailSender;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.CourseFixtures;
import org.example.ohara.support.CourseFixtures.PublishedCourse;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EnrollmentIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;
    @Autowired InMemoryEmailSender emails;

    TestUsers users;
    OrgFixtures fx;
    CourseFixtures courses;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        courses = new CourseFixtures(fx);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private record CollegeSetup(Org org, PublishedCourse course, UUID cohortId, UUID offeringId) {
    }

    private ResultActions place(Org org, UUID cohortId, TestUser learner) throws Exception {
        return fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());
    }

    private UUID createOffering(Org org, UUID courseId, UUID cohortId, String extraJson) throws Exception {
        String json = fx.send(post("/api/orgs/{orgId}/offerings", org.id()), org.admin(),
                "{\"courseId\":\"%s\",\"cohortId\":\"%s\"%s}".formatted(courseId, cohortId, extraJson))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private void open(Org org, UUID offeringId) throws Exception {
        fx.send(post("/api/orgs/{orgId}/offerings/{id}/open", org.id(), offeringId), org.admin(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    /** A college with an org-owned published course offered (open) to a cohort. */
    private CollegeSetup college() throws Exception {
        Org org = fx.orgWithAdmin();
        PublishedCourse course = courses.published(org.admin(), org.id());
        UUID cohortId = fx.cohort(org, "B" + UUID.randomUUID().toString().substring(0, 4));
        UUID offeringId = createOffering(org, course.courseId(), cohortId, "");
        open(org, offeringId);
        return new CollegeSetup(org, course, cohortId, offeringId);
    }

    private ResultActions lesson(TestUser as, UUID lessonId) throws Exception {
        return fx.send(get("/api/lessons/{id}", lessonId), as, null);
    }

    private PublishedCourse b2cCourse() throws Exception {
        return courses.published(fx.platformAdmin(), null);
    }

    private UUID openB2COffering(UUID courseId) throws Exception {
        String json = fx.send(post("/api/admin/offerings"), fx.platformAdmin(),
                "{\"courseId\":\"%s\",\"accessDurationDays\":180,\"listPriceMinor\":100000,\"currency\":\"INR\"}".formatted(courseId))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        fx.send(post("/api/admin/offerings/{id}/open", id), fx.platformAdmin(), null).andExpect(status().isOk());
        return id;
    }

    private ResultActions grant(TestUser as, UUID offeringId, String email, String reference) throws Exception {
        return fx.send(post("/api/admin/offerings/{id}/grants", offeringId), as,
            "{\"email\":\"%s\",\"firstName\":\"Neha\",\"lastName\":\"Shah\",\"externalReference\":\"%s\"}".formatted(email, reference));
    }

    // --- B2B: cohorts drive access -------------------------------------------------------------------

    @Test
    void openingAnOfferingEnrollsEveryoneInTheCohortAndLaterJoinersToo() throws Exception {
        Org org = fx.orgWithAdmin();
        PublishedCourse course = courses.published(org.admin(), org.id());
        UUID cohortId = fx.cohort(org, "CSE");
        TestUser early = fx.member(org, "LEARNER");
        place(org, cohortId, early);
        UUID offeringId = createOffering(org, course.courseId(), cohortId, "");

        fx.send(get("/api/me/courses"), early, null).andExpect(jsonPath("$", hasSize(0)));
        open(org, offeringId);

        String mine = fx.send(get("/api/me/courses"), early, null)
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].channel").value("B2B"))
            .andExpect(jsonPath("$[0].hasAccess").value(true))
            .andExpect(jsonPath("$[0].courseVersionId").value(course.versionId().toString()))
            .andReturn().getResponse().getContentAsString();
        String enrollmentId = JsonPath.read(mine, "$[0].enrollmentId");
        fx.send(get("/api/enrollments/{id}/curriculum", enrollmentId), early, null)
            .andExpect(jsonPath("$.hasAccess").value(true))
            .andExpect(jsonPath("$.modules[0].lessons[1].locked").value(false));
        lesson(early, course.lockedLessonId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contents[0].body").value("Pick a pivot..."))
            .andExpect(jsonPath("$.enrollmentId").value(enrollmentId));

        TestUser late = fx.member(org, "LEARNER");
        place(org, cohortId, late);
        lesson(late, course.lockedLessonId()).andExpect(status().isOk());

        fx.send(get("/api/orgs/{orgId}/offerings/{id}/enrollments", org.id(), offeringId), org.admin(), null)
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void leavingOrTransferringOutOfTheCohortEndsAccessButKeepsTheEnrollment() throws Exception {
        CollegeSetup s = college();
        TestUser removed = fx.member(s.org(), "LEARNER");
        TestUser transferred = fx.member(s.org(), "LEARNER");
        place(s.org(), s.cohortId(), removed);
        place(s.org(), s.cohortId(), transferred);
        UUID otherCohort = fx.cohort(s.org(), "OTHER");

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), removed.id()),
            s.org().admin(), null).andExpect(status().isNoContent());
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}/transfer", s.org().id(), s.cohortId(), transferred.id()),
            s.org().admin(), "{\"toCohortId\":\"%s\"}".formatted(otherCohort)).andExpect(status().isOk());

        for (TestUser learner : new TestUser[]{removed, transferred}) {
            lesson(learner, s.course().lockedLessonId())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("access_expired"));
            fx.send(get("/api/me/courses"), learner, null)
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].hasAccess").value(false));
        }
        String reasons = jdbc.sql("SELECT string_agg(revoked_reason, ',' ORDER BY revoked_reason) FROM access_grant g "
                + "JOIN enrollment e ON e.id = g.enrollment_id WHERE e.course_offering_id = :o")
            .param("o", s.offeringId()).query(String.class).single();
        assertThat(reasons).isEqualTo("REMOVED_FROM_COHORT,TRANSFERRED");
    }

    @Test
    void theOfferingWindowControlsWhenAccessStarts() throws Exception {
        Org org = fx.orgWithAdmin();
        PublishedCourse course = courses.published(org.admin(), org.id());
        UUID cohortId = fx.cohort(org, "NEXT");
        TestUser learner = fx.member(org, "LEARNER");
        place(org, cohortId, learner);
        Instant nextMonth = Instant.now().plus(30, ChronoUnit.DAYS);
        UUID offeringId = createOffering(org, course.courseId(), cohortId, ",\"startsAt\":\"%s\"".formatted(nextMonth));
        open(org, offeringId);

        lesson(learner, course.lockedLessonId()).andExpect(status().isForbidden());
        lesson(learner, course.previewLessonId()).andExpect(status().isOk());

        fx.send(patch("/api/orgs/{orgId}/offerings/{id}", org.id(), offeringId), org.admin(),
                "{\"startsAt\":\"%s\"}".formatted(Instant.now().minus(1, ChronoUnit.DAYS)))
            .andExpect(status().isOk());
        lesson(learner, course.lockedLessonId()).andExpect(status().isOk());
    }

    @Test
    void suspendingTheCollegeSuspendsItsLearnersAccess() throws Exception {
        CollegeSetup s = college();
        TestUser learner = fx.member(s.org(), "LEARNER");
        place(s.org(), s.cohortId(), learner);

        fx.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/organizations/{id}/status",
            s.org().id()), fx.platformAdmin(), "{\"status\":\"SUSPENDED\"}").andExpect(status().isOk());

        lesson(learner, s.course().lockedLessonId())
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("organization_inactive"));
    }

    @Test
    void learnersStayOnTheVersionTheyEnrolledIn() throws Exception {
        CollegeSetup s = college();
        TestUser before = fx.member(s.org(), "LEARNER");
        place(s.org(), s.cohortId(), before);

        String base = "/api/courses/" + s.course().courseId() + "/versions";
        String v2 = fx.send(post(base), s.org().admin(), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String v2Id = JsonPath.read(v2, "$.id");
        String v2Lesson = JsonPath.read(v2, "$.modules[0].lessons[1].id");
        fx.send(post(base + "/" + v2Id + "/publish"), s.org().admin(), "{}").andExpect(status().isOk());

        // A second offering of the same course picks up v2 for its learners.
        UUID cohort2 = fx.cohort(s.org(), "LATER");
        TestUser after = fx.member(s.org(), "LEARNER");
        place(s.org(), cohort2, after);
        open(s.org(), createOffering(s.org(), s.course().courseId(), cohort2, ""));

        lesson(before, s.course().lockedLessonId()).andExpect(status().isOk());
        lesson(before, UUID.fromString(v2Lesson)).andExpect(status().isNotFound());
        lesson(after, UUID.fromString(v2Lesson)).andExpect(status().isOk());
        lesson(after, s.course().lockedLessonId()).andExpect(status().isNotFound());
    }

    @Test
    void previewLessonsAreOpenStaffSeeEverythingOthersSeeNothing() throws Exception {
        CollegeSetup s = college();
        TestUser stranger = users.user();

        lesson(stranger, s.course().previewLessonId()).andExpect(status().isOk()).andExpect(jsonPath("$.enrollmentId").doesNotExist());
        lesson(stranger, s.course().lockedLessonId()).andExpect(status().isNotFound());
        lesson(s.org().admin(), s.course().lockedLessonId()).andExpect(status().isOk());
        lesson(fx.platformAdmin(), s.course().lockedLessonId()).andExpect(status().isOk());
    }

    @Test
    void orgAdminCanRevokeAnEnrollment() throws Exception {
        CollegeSetup s = college();
        TestUser learner = fx.member(s.org(), "LEARNER");
        place(s.org(), s.cohortId(), learner);
        String enrollmentId = JsonPath.read(fx.send(get("/api/me/courses"), learner, null).andReturn().getResponse().getContentAsString(),
            "$[0].enrollmentId");

        fx.send(post("/api/orgs/{orgId}/enrollments/{id}/revoke", s.org().id(), enrollmentId), s.org().admin(), null)
            .andExpect(status().isNoContent());

        lesson(learner, s.course().lockedLessonId()).andExpect(status().isForbidden());
        Org other = fx.orgWithAdmin();
        fx.send(post("/api/orgs/{orgId}/enrollments/{id}/revoke", other.id(), enrollmentId), other.admin(), null)
            .andExpect(status().isNotFound());
    }

    @Test
    void offeringRulesAreEnforced() throws Exception {
        CollegeSetup s = college();
        PublishedCourse platformCourse = b2cCourse();

        // Same course to the same cohort twice.
        fx.send(post("/api/orgs/{orgId}/offerings", s.org().id()), s.org().admin(),
                "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(s.course().courseId(), s.cohortId()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_offered"));
        // A platform course needs a platform admin (licence).
        fx.send(post("/api/orgs/{orgId}/offerings", s.org().id()), s.org().admin(),
                "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(platformCourse.courseId(), s.cohortId()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("platform_course_needs_licence"));
        fx.send(post("/api/orgs/{orgId}/offerings", s.org().id()), fx.platformAdmin(),
                "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(platformCourse.courseId(), s.cohortId()))
            .andExpect(status().isCreated());
        // Another college's course or cohort isn't reachable.
        Org other = fx.orgWithAdmin();
        fx.send(post("/api/orgs/{orgId}/offerings", other.id()), other.admin(),
                "{\"courseId\":\"%s\"}".formatted(s.course().courseId()))
            .andExpect(status().isNotFound());
        fx.send(get("/api/orgs/{orgId}/offerings/{id}", other.id(), s.offeringId()), other.admin(), null)
            .andExpect(status().isNotFound());
    }

    // --- B2C: the contract team grants access after offline payment ---------------------------------

    @Test
    void contractTeamGrantsAccessToANewPersonWithAPaymentReference() throws Exception {
        PublishedCourse course = b2cCourse();
        UUID offeringId = openB2COffering(course.courseId());
        TestUser contractTeam = users.withPlatformRole("ROLE_ENROLLMENT_MANAGER");
        String email = "buyer-" + UUID.randomUUID() + "@example.com";

        String granted = grant(contractTeam, offeringId, email, "UTR-1001")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userCreated").value(true))
            .andExpect(jsonPath("$.source").value("CONTRACT"))
            .andExpect(jsonPath("$.externalReference").value("UTR-1001"))
            .andExpect(jsonPath("$.validUntil").exists())
            .andReturn().getResponse().getContentAsString();
        jobs.drain();
        assertThat(emails.sentTo(email)).anySatisfy(m -> assertThat(m.subject()).contains("account is ready"));

        // Re-submitting the same payment is harmless; a different one while access is live is a conflict.
        grant(contractTeam, offeringId, email, "UTR-1001")
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false))
            .andExpect(jsonPath("$.grantId").value((String) JsonPath.read(granted, "$.grantId")));
        grant(contractTeam, offeringId, email, "UTR-2002")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_has_access"));
    }

    @Test
    void grantExtendRefundAndReconciliation() throws Exception {
        PublishedCourse course = b2cCourse();
        UUID offeringId = openB2COffering(course.courseId());
        TestUser contractTeam = users.withPlatformRole("ROLE_ENROLLMENT_MANAGER");
        TestUser buyer = users.user();
        Instant started = Instant.now().minusSeconds(1);
        String ref1 = "UTR-" + UUID.randomUUID();
        String ref2 = "UTR-" + UUID.randomUUID();

        String first = grant(contractTeam, offeringId, buyer.email(), ref1).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        lesson(buyer, course.lockedLessonId()).andExpect(status().isOk());
        fx.send(get("/api/me/courses"), buyer, null).andExpect(jsonPath("$[0].channel").value("B2C"));

        String extended = fx.send(post("/api/admin/grants/{id}/extend", (String) JsonPath.read(first, "$.grantId")), contractTeam,
                "{\"validUntil\":\"%s\",\"externalReference\":\"%s\"}".formatted(Instant.now().plus(400, ChronoUnit.DAYS), ref2))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.externalReference").value(ref2))
            .andReturn().getResponse().getContentAsString();
        lesson(buyer, course.lockedLessonId()).andExpect(status().isOk());

        fx.send(post("/api/admin/grants/{id}/revoke", (String) JsonPath.read(extended, "$.grantId")), contractTeam,
                "{\"reason\":\"REFUNDED_OFFLINE\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.revokedReason").value("REFUNDED_OFFLINE"));
        lesson(buyer, course.lockedLessonId()).andExpect(status().isForbidden());
        fx.send(get("/api/me/courses"), buyer, null).andExpect(jsonPath("$[0].status").value("CANCELLED"));

        fx.send(get("/api/admin/access-grants").param("source", "CONTRACT").param("from", started.toString()), contractTeam, null)
            .andExpect(jsonPath("$[?(@.externalReference == '%s')].revokedReason".formatted(ref1)).value("REPLACED"))
            .andExpect(jsonPath("$[?(@.externalReference == '%s')].grantedBy".formatted(ref2)).value(contractTeam.email()));
        fx.send(get("/api/admin/access-grants.csv").param("from", started.toString()), contractTeam, null)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(ref2)));
    }

    @Test
    void onlyTheContractTeamGrantsAndCohortAccessIsNotTheirs() throws Exception {
        CollegeSetup s = college();
        UUID offeringId = openB2COffering(b2cCourse().courseId());
        TestUser contractTeam = users.withPlatformRole("ROLE_ENROLLMENT_MANAGER");

        grant(users.user(), offeringId, "x-" + UUID.randomUUID() + "@example.com", "R").andExpect(status().isForbidden());
        grant(s.org().admin(), offeringId, "x-" + UUID.randomUUID() + "@example.com", "R").andExpect(status().isForbidden());
        // B2C grants only go on B2C offerings.
        grant(contractTeam, s.offeringId(), "x-" + UUID.randomUUID() + "@example.com", "R").andExpect(status().isNotFound());

        TestUser learner = fx.member(s.org(), "LEARNER");
        place(s.org(), s.cohortId(), learner);
        UUID cohortGrant = jdbc.sql("SELECT id FROM access_grant WHERE user_id = :u").param("u", learner.id()).query(UUID.class).single();
        fx.send(post("/api/admin/grants/{id}/revoke", cohortGrant), contractTeam, "{\"reason\":\"ADMIN\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cohort_grant"));
    }

    @Test
    void grantsNeedAnOpenOffering() throws Exception {
        PublishedCourse course = b2cCourse();
        UUID offeringId = openB2COffering(course.courseId());
        fx.send(post("/api/admin/offerings/{id}/close", offeringId), fx.platformAdmin(), null).andExpect(status().isOk());

        grant(fx.platformAdmin(), offeringId, "y-" + UUID.randomUUID() + "@example.com", "R1")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("offering_not_open"));
    }
}
