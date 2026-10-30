package org.example.ohara.enrollment.api;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProgressIntegrationTest {

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

    /** A college course with a 60-second video lesson and a reading lesson, offered to a cohort with one learner. */
    private record Setup(Org org, String courseId, String videoKey, String readingKey, String videoLessonId,
                         UUID offeringId, UUID cohortId, TestUser learner, String enrollmentId) {
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private Setup setup(boolean pinned) throws Exception {
        Org org = fx.orgWithAdmin();
        String course = json(fx.send(post("/api/courses"), org.admin(), "{\"title\":\"Graphs\",\"slug\":\"g-%s\",\"ownerOrganizationId\":\"%s\"}"
            .formatted(UUID.randomUUID().toString().substring(0, 10), org.id())).andExpect(status().isCreated()));
        String courseId = JsonPath.read(course, "$.id");
        String versionId = JsonPath.read(course, "$.draftVersionId");
        String base = "/api/courses/" + courseId + "/versions/" + versionId;
        fx.send(patch(base), org.admin(), "{\"description\":\"BFS and DFS\"}").andExpect(status().isOk());
        String moduleId = JsonPath.read(json(fx.send(post(base + "/modules"), org.admin(), "{\"title\":\"Traversal\"}")), "$.modules[0].id");
        fx.send(post(base + "/modules/" + moduleId + "/lessons"), org.admin(), "{\"title\":\"BFS video\",\"type\":\"VIDEO\",\"durationSeconds\":60}");
        String tree = json(fx.send(post(base + "/modules/" + moduleId + "/lessons"), org.admin(), "{\"title\":\"DFS notes\",\"type\":\"READING\"}"));
        String videoId = JsonPath.read(tree, "$.modules[0].lessons[0].id");
        String readingId = JsonPath.read(tree, "$.modules[0].lessons[1].id");
        fx.send(post(base + "/lessons/" + videoId + "/contents"), org.admin(), "{\"kind\":\"EXTERNAL_LINK\",\"externalUrl\":\"https://video.example/bfs\"}");
        fx.send(post(base + "/lessons/" + readingId + "/contents"), org.admin(), "{\"kind\":\"TEXT\",\"body\":\"Stack it.\"}");
        tree = json(fx.send(post(base + "/publish"), org.admin(), "{}").andExpect(status().isOk()));

        UUID cohortId = fx.cohort(org, "C" + UUID.randomUUID().toString().substring(0, 4));
        TestUser learner = fx.member(org, "LEARNER");
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());
        String pin = pinned ? ",\"pinnedVersionId\":\"%s\"".formatted(versionId) : "";
        UUID offeringId = UUID.fromString(JsonPath.read(json(fx.send(post("/api/orgs/{orgId}/offerings", org.id()), org.admin(),
            "{\"courseId\":\"%s\",\"cohortId\":\"%s\"%s}".formatted(courseId, cohortId, pin)).andExpect(status().isCreated())), "$.id"));
        fx.send(post("/api/orgs/{orgId}/offerings/{id}/open", org.id(), offeringId), org.admin(), null).andExpect(status().isOk());
        String enrollmentId = JsonPath.read(json(fx.send(get("/api/me/courses"), learner, null)), "$[0].enrollmentId");

        return new Setup(org, courseId, JsonPath.read(tree, "$.modules[0].lessons[0].lessonKey"),
            JsonPath.read(tree, "$.modules[0].lessons[1].lessonKey"), videoId, offeringId, cohortId, learner, enrollmentId);
    }

    private ResultActions heartbeat(Setup s, String lessonKey, long position, long watchedDelta) throws Exception {
        return fx.send(put("/api/enrollments/{e}/lessons/{k}/progress", s.enrollmentId(), lessonKey), s.learner(),
            "{\"positionMs\":%d,\"watchedDeltaMs\":%d,\"clientTs\":\"%s\"}".formatted(position, watchedDelta, Instant.now()));
    }

    /** Pretends the last heartbeat happened {@code seconds} ago. */
    private void rewind(Setup s, String lessonKey, int seconds) {
        jdbc.sql("UPDATE lesson_progress SET updated_at = updated_at - make_interval(secs => :s) WHERE enrollment_id = :e AND lesson_key = :k")
            .param("s", seconds).param("e", UUID.fromString(s.enrollmentId())).param("k", UUID.fromString(lessonKey)).update();
    }

    @Test
    void heartbeatsRecordPositionForResumeAndContinueWatching() throws Exception {
        Setup s = setup(false);

        heartbeat(s, s.videoKey(), 12_000, 12_000).andExpect(status().isNoContent());

        fx.send(get("/api/enrollments/{e}/progress", s.enrollmentId()), s.learner(), null)
            .andExpect(jsonPath("$.lessons[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.lessons[0].positionMs").value(12_000))
            .andExpect(jsonPath("$.lessons[0].durationMs").value(60_000))
            .andExpect(jsonPath("$.totalLessons").value(2));
        fx.send(get("/api/lessons/{id}", s.videoLessonId()), s.learner(), null)
            .andExpect(jsonPath("$.resumePositionMs").value(12_000));
        fx.send(get("/api/me/continue-watching"), s.learner(), null)
            .andExpect(jsonPath("$[0].lessonKey").value(s.videoKey()))
            .andExpect(jsonPath("$[0].positionMs").value(12_000));
        fx.send(get("/api/enrollments/{e}/curriculum", s.enrollmentId()), s.learner(), null)
            .andExpect(jsonPath("$.modules[0].lessons[0].progress").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.modules[0].lessons[1].progress").value("NOT_STARTED"));
    }

    @Test
    void claimingMoreWatchTimeThanElapsedDoesNotCompleteAVideo() throws Exception {
        Setup s = setup(false);

        heartbeat(s, s.videoKey(), 30_000, 30_000).andExpect(status().isNoContent());
        heartbeat(s, s.videoKey(), 60_000, 600_000).andExpect(status().isNoContent());

        fx.send(get("/api/enrollments/{e}/progress", s.enrollmentId()), s.learner(), null)
            .andExpect(jsonPath("$.lessons[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.progressPercent").value(0));
    }

    @Test
    void finishingEveryLessonCompletesTheEnrollment() throws Exception {
        Setup s = setup(false);

        heartbeat(s, s.videoKey(), 30_000, 30_000);
        rewind(s, s.videoKey(), 30);
        heartbeat(s, s.videoKey(), 58_000, 28_000).andExpect(status().isNoContent());
        fx.send(get("/api/enrollments/{e}/progress", s.enrollmentId()), s.learner(), null)
            .andExpect(jsonPath("$.progressPercent").value(50.0));

        fx.send(post("/api/enrollments/{e}/lessons/{k}/complete", s.enrollmentId(), s.readingKey()), s.learner(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.progressPercent").value(100.0))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedLessons").value(2));
        // Completing again is harmless.
        fx.send(post("/api/enrollments/{e}/lessons/{k}/complete", s.enrollmentId(), s.readingKey()), s.learner(), null)
            .andExpect(status().isOk());
        fx.send(get("/api/me/courses"), s.learner(), null).andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void progressNeedsCurrentAccessThroughThisEnrollment() throws Exception {
        Setup s = setup(false);
        TestUser stranger = users.user();

        fx.send(put("/api/enrollments/{e}/lessons/{k}/progress", s.enrollmentId(), s.videoKey()), stranger,
            "{\"watchedDeltaMs\":1000}").andExpect(status().isNotFound());
        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), s.learner().id()),
            s.org().admin(), null).andExpect(status().isNoContent());
        heartbeat(s, s.videoKey(), 1000, 1000)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("access_expired"));
        fx.send(put("/api/enrollments/{e}/lessons/{k}/progress", s.enrollmentId(), UUID.randomUUID()), s.learner(),
            "{\"watchedDeltaMs\":1000}").andExpect(status().isNotFound());
    }

    @Test
    void migratingToANewVersionKeepsCompletedLessons() throws Exception {
        Setup s = setup(false);
        fx.send(post("/api/enrollments/{e}/lessons/{k}/complete", s.enrollmentId(), s.readingKey()), s.learner(), null);

        // v2 adds a third lesson.
        String v2 = json(fx.send(post("/api/courses/{id}/versions", s.courseId()), s.org().admin(), null));
        String v2Id = JsonPath.read(v2, "$.id");
        String base = "/api/courses/" + s.courseId() + "/versions/" + v2Id;
        String moduleId = JsonPath.read(v2, "$.modules[0].id");
        String tree = json(fx.send(post(base + "/modules/" + moduleId + "/lessons"), s.org().admin(), "{\"title\":\"Dijkstra\",\"type\":\"READING\"}"));
        String newLesson = JsonPath.read(tree, "$.modules[0].lessons[2].id");
        fx.send(post(base + "/lessons/" + newLesson + "/contents"), s.org().admin(), "{\"kind\":\"TEXT\",\"body\":\"Greedy.\"}");
        fx.send(post(base + "/publish"), s.org().admin(), "{}").andExpect(status().isOk());

        fx.send(post("/api/enrollments/{e}/migrate-version", s.enrollmentId()), s.learner(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toVersionId").value(v2Id))
            .andExpect(jsonPath("$.progressPercent").value(33.33));
        fx.send(get("/api/enrollments/{e}/progress", s.enrollmentId()), s.learner(), null)
            .andExpect(jsonPath("$.totalLessons").value(3))
            .andExpect(jsonPath("$.lessons[?(@.lessonKey == '%s')].status".formatted(s.readingKey())).value("COMPLETED"));
        fx.send(get("/api/lessons/{id}", newLesson), s.learner(), null).andExpect(status().isOk());
        fx.send(post("/api/enrollments/{e}/migrate-version", s.enrollmentId()), s.learner(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_latest"));
    }

    @Test
    void pinnedOfferingsOnlyLetAdminsMoveLearners() throws Exception {
        Setup s = setup(true);
        String v2 = json(fx.send(post("/api/courses/{id}/versions", s.courseId()), s.org().admin(), null));
        fx.send(post("/api/courses/{id}/versions/{v}/publish", s.courseId(), (String) JsonPath.read(v2, "$.id")), s.org().admin(), "{}")
            .andExpect(status().isOk());

        fx.send(post("/api/enrollments/{e}/migrate-version", s.enrollmentId()), s.learner(), null)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("version_pinned"));
        fx.send(post("/api/enrollments/{e}/migrate-version", s.enrollmentId()), s.org().admin(), null)
            .andExpect(status().isOk());
        fx.send(post("/api/enrollments/{e}/migrate-version", s.enrollmentId()), fx.orgWithAdmin().admin(), null)
            .andExpect(status().isNotFound());
    }
}
