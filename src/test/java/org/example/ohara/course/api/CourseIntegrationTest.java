package org.example.ohara.course.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class CourseIntegrationTest {

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

    // --- helpers -------------------------------------------------------------------------------------

    private static String slug() {
        return "c-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private record Draft(String courseId, String versionId) {
        String path() {
            return "/api/courses/" + courseId + "/versions/" + versionId;
        }
    }

    private Draft createCourse(TestUser as, UUID orgId) throws Exception {
        String org = orgId == null ? "" : ",\"ownerOrganizationId\":\"%s\"".formatted(orgId);
        String json = body(fx.send(post("/api/courses"), as, "{\"title\":\"Data Structures\",\"slug\":\"%s\"%s}".formatted(slug(), org))
            .andExpect(status().isCreated()));
        return new Draft(JsonPath.read(json, "$.id"), JsonPath.read(json, "$.draftVersionId"));
    }

    /** Fills the draft so it passes the publish checklist: description, 2 modules, lessons with content. */
    private String buildPublishable(Draft d, TestUser as) throws Exception {
        fx.send(patch(d.path()), as, "{\"description\":\"Arrays, lists, trees.\",\"level\":\"BEGINNER\"}").andExpect(status().isOk());
        String tree = body(fx.send(post(d.path() + "/modules"), as, "{\"title\":\"Basics\"}"));
        tree = body(fx.send(post(d.path() + "/modules"), as, "{\"title\":\"Trees\"}"));
        List<String> moduleIds = JsonPath.read(tree, "$.modules[*].id");
        for (String moduleId : moduleIds) {
            tree = body(fx.send(post(d.path() + "/modules/" + moduleId + "/lessons"), as, "{\"title\":\"Intro\",\"type\":\"READING\",\"preview\":true}")
                .andExpect(status().isCreated()));
        }
        List<String> lessonIds = JsonPath.read(tree, "$.modules[*].lessons[*].id");
        for (String lessonId : lessonIds) {
            tree = body(fx.send(post(d.path() + "/lessons/" + lessonId + "/contents"), as,
                "{\"kind\":\"TEXT\",\"title\":\"Notes\",\"body\":\"# Hello\"}").andExpect(status().isCreated()));
        }
        return tree;
    }

    // --- creation & permissions ----------------------------------------------------------------------

    @Test
    void platformCoursesAreCreatedByPlatformAdminsOnly() throws Exception {
        TestUser regular = users.user();
        fx.send(post("/api/courses"), regular, "{\"title\":\"X\",\"slug\":\"%s\"}".formatted(slug())).andExpect(status().isForbidden());

        String s = slug();
        fx.send(post("/api/courses"), fx.platformAdmin(), "{\"title\":\"X\",\"slug\":\"%s\"}".formatted(s))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.draftVersionId").exists());
        fx.send(post("/api/courses"), fx.platformAdmin(), "{\"title\":\"Y\",\"slug\":\"%s\"}".formatted(s))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("slug_taken"));
    }

    @Test
    void organizationInstructorsCreatePrivateCoursesButLearnersCannot() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser instructor = fx.member(org, "INSTRUCTOR");
        TestUser learner = fx.member(org, "LEARNER");

        fx.send(post("/api/courses"), instructor, "{\"title\":\"Org course\",\"slug\":\"%s\",\"ownerOrganizationId\":\"%s\"}"
                .formatted(slug(), org.id()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visibility").value("ORG_PRIVATE"))
            .andExpect(jsonPath("$.ownerOrganizationId").value(org.id().toString()));
        fx.send(post("/api/courses"), learner, "{\"title\":\"Nope\",\"slug\":\"%s\",\"ownerOrganizationId\":\"%s\"}"
                .formatted(slug(), org.id()))
            .andExpect(status().isForbidden());
        fx.send(get("/api/courses"), instructor, null).andExpect(jsonPath("$.totalItems").value(1));
        fx.send(get("/api/orgs/{orgId}/courses", org.id()), org.admin(), null).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void nonStaffCantSeeACourse() throws Exception {
        Draft d = createCourse(fx.platformAdmin(), null);
        TestUser stranger = users.user();

        fx.send(get("/api/courses/{id}", d.courseId()), stranger, null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("course_not_found"));
        fx.send(get(d.path()), stranger, null).andExpect(status().isNotFound());
    }

    // --- authoring & publishing ----------------------------------------------------------------------

    @Test
    void draftIsBuiltReorderedAndPublishedAfterTheChecklistPasses() throws Exception {
        TestUser admin = fx.platformAdmin();
        Draft d = createCourse(admin, null);

        fx.send(post(d.path() + "/publish"), admin, "{}")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("not_publishable"))
            .andExpect(jsonPath("$.errors", hasSize(2)));

        String tree = buildPublishable(d, admin);
        List<String> moduleIds = JsonPath.read(tree, "$.modules[*].id");
        String reordered = "{\"ids\":[\"%s\",\"%s\"]}".formatted(moduleIds.get(1), moduleIds.get(0));
        fx.send(put(d.path() + "/modules/order"), admin, reordered)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules[0].title").value("Trees"))
            .andExpect(jsonPath("$.modules[0].position").value(0));
        fx.send(put(d.path() + "/modules/order"), admin, "{\"ids\":[\"%s\"]}".formatted(moduleIds.get(0)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_order"));

        fx.send(post(d.path() + "/publish"), admin, "{\"changeNotes\":\"First release\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.changeNotes").value("First release"));
        fx.send(get("/api/courses/{id}", d.courseId()), admin, null)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.currentPublishedVersionId").value(d.versionId()))
            .andExpect(jsonPath("$.draftVersionId").doesNotExist());
    }

    @Test
    void publishedVersionsAreFrozenInTheApiAndInTheDatabase() throws Exception {
        TestUser admin = fx.platformAdmin();
        Draft d = createCourse(admin, null);
        String tree = buildPublishable(d, admin);
        fx.send(post(d.path() + "/publish"), admin, "{}").andExpect(status().isOk());

        fx.send(post(d.path() + "/modules"), admin, "{\"title\":\"Late addition\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_frozen"));
        fx.send(patch(d.path()), admin, "{\"title\":\"Renamed\"}").andExpect(status().isConflict());

        // Even code that bypasses the service can't change a published curriculum.
        String lessonId = JsonPath.read(tree, "$.modules[0].lessons[0].id");
        assertThatThrownBy(() -> jdbc.sql("UPDATE lesson SET title = 'hacked' WHERE id = :id")
            .param("id", UUID.fromString(lessonId)).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be modified");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM course_version WHERE id = :id")
            .param("id", UUID.fromString(d.versionId())).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newDraftCopiesTheCurriculumKeepingLessonKeys() throws Exception {
        TestUser admin = fx.platformAdmin();
        Draft v1 = createCourse(admin, null);
        String v1Tree = buildPublishable(v1, admin);
        fx.send(post(v1.path() + "/publish"), admin, "{}").andExpect(status().isOk());

        String v2Tree = body(fx.send(post("/api/courses/{id}/versions", v1.courseId()), admin, null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNumber").value(2))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.basedOnVersionId").value(v1.versionId())));
        fx.send(post("/api/courses/{id}/versions", v1.courseId()), admin, null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("draft_exists"));

        List<String> v1Keys = JsonPath.read(v1Tree, "$.modules[*].lessons[*].lessonKey");
        List<String> v2Keys = JsonPath.read(v2Tree, "$.modules[*].lessons[*].lessonKey");
        List<String> v1Ids = JsonPath.read(v1Tree, "$.modules[*].lessons[*].id");
        List<String> v2Ids = JsonPath.read(v2Tree, "$.modules[*].lessons[*].id");
        assertThat(v2Keys).containsExactlyElementsOf(v1Keys);
        assertThat(v2Ids).doesNotContainAnyElementsOf(v1Ids);

        // Editing the copy leaves the published original untouched.
        Draft v2 = new Draft(v1.courseId(), JsonPath.read(v2Tree, "$.id"));
        String v2Lesson = v2Ids.getFirst();
        fx.send(patch(v2.path() + "/lessons/" + v2Lesson), admin, "{\"title\":\"Intro (revised)\"}").andExpect(status().isOk());
        fx.send(get(v1.path()), admin, null).andExpect(jsonPath("$.modules[0].lessons[0].title").value("Intro"));

        fx.send(post(v2.path() + "/publish"), admin, "{}").andExpect(status().isOk());
        fx.send(get("/api/courses/{id}", v1.courseId()), admin, null)
            .andExpect(jsonPath("$.currentPublishedVersionId").value(v2.versionId()));
        fx.send(post(v2.path() + "/archive"), admin, null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("current_version"));
        fx.send(post(v1.path() + "/archive"), admin, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void removingALessonKeepsPositionsContiguous() throws Exception {
        TestUser admin = fx.platformAdmin();
        Draft d = createCourse(admin, null);
        String moduleId = JsonPath.read(body(fx.send(post(d.path() + "/modules"), admin, "{\"title\":\"M\"}")), "$.modules[0].id");
        String tree = null;
        for (String title : List.of("A", "B", "C")) {
            tree = body(fx.send(post(d.path() + "/modules/" + moduleId + "/lessons"), admin,
                "{\"title\":\"%s\",\"type\":\"READING\"}".formatted(title)));
        }
        String middle = JsonPath.read(tree, "$.modules[0].lessons[1].id");

        fx.send(delete(d.path() + "/lessons/" + middle), admin, null)
            .andExpect(jsonPath("$.modules[0].lessons", hasSize(2)))
            .andExpect(jsonPath("$.modules[0].lessons[1].title").value("C"))
            .andExpect(jsonPath("$.modules[0].lessons[1].position").value(1));
    }

    @Test
    void firstDraftCantBeDiscarded() throws Exception {
        Draft d = createCourse(fx.platformAdmin(), null);

        fx.send(delete(d.path()), fx.platformAdmin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("only_version"));
    }

    // --- staff ---------------------------------------------------------------------------------------

    @Test
    void staffRolesLimitWhatEachPersonCanDo() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser owner = fx.member(org, "INSTRUCTOR");
        TestUser coInstructor = fx.member(org, "INSTRUCTOR");
        TestUser ta = fx.member(org, "INSTRUCTOR");
        TestUser learner = fx.member(org, "LEARNER");
        Draft d = createCourse(owner, org.id());

        fx.send(put("/api/courses/{id}/instructors", d.courseId()), owner,
            "{\"userId\":\"%s\",\"role\":\"INSTRUCTOR\"}".formatted(coInstructor.id())).andExpect(status().isOk());
        fx.send(put("/api/courses/{id}/instructors", d.courseId()), owner,
            "{\"userId\":\"%s\",\"role\":\"TA\"}".formatted(ta.id())).andExpect(status().isOk());
        fx.send(put("/api/courses/{id}/instructors", d.courseId()), owner,
                "{\"userId\":\"%s\",\"role\":\"TA\"}".formatted(learner.id()))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("user_not_eligible"));

        buildPublishable(d, coInstructor);
        fx.send(post(d.path() + "/publish"), coInstructor, "{}").andExpect(status().isForbidden());
        fx.send(get(d.path()), ta, null).andExpect(status().isOk());
        fx.send(post(d.path() + "/modules"), ta, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        // The org's admin manages every org course without being on its staff.
        fx.send(post(d.path() + "/publish"), org.admin(), "{}").andExpect(status().isOk());

        fx.send(delete("/api/courses/{id}/instructors/{uid}", d.courseId(), owner.id()), owner, null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_owner"));
    }

    @Test
    void courseStaffLoseAccessWhenTheyLeaveTheOrganization() throws Exception {
        Org org = fx.orgWithAdmin();
        TestUser instructor = fx.member(org, "INSTRUCTOR");
        Draft d = createCourse(instructor, org.id());

        fx.send(patch("/api/orgs/{orgId}/members/{userId}", org.id(), instructor.id()), org.admin(), "{\"status\":\"REMOVED\"}")
            .andExpect(status().isOk());

        fx.send(get(d.path()), instructor, null).andExpect(status().isNotFound());
    }

    @Test
    void idsFromAnotherCourseDontResolve() throws Exception {
        TestUser admin = fx.platformAdmin();
        Draft a = createCourse(admin, null);
        Draft b = createCourse(admin, null);
        String moduleOfA = JsonPath.read(body(fx.send(post(a.path() + "/modules"), admin, "{\"title\":\"A\"}")), "$.modules[0].id");

        fx.send(post(b.path() + "/modules/" + moduleOfA + "/lessons"), admin, "{\"title\":\"x\",\"type\":\"READING\"}")
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("module_not_found"));
        fx.send(get("/api/courses/" + b.courseId() + "/versions/" + a.versionId()), admin, null)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("version_not_found"));
    }
}
