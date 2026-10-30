package org.example.ohara.live.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.live.application.LiveStreamEvents;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LiveSessionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LiveStreamEvents streamEvents;

    TestUsers users;
    OrgFixtures fx;
    CourseFixtures courses;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        courses = new CourseFixtures(fx);
    }

    /** A college with an open cohort offering, two placed learners and an instructor. */
    private record Setup(Org org, PublishedCourse course, UUID cohortId, UUID offeringId, TestUser instructor,
                         TestUser learner, TestUser otherLearner) {
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private Setup setup() throws Exception {
        Org org = fx.orgWithAdmin();
        PublishedCourse course = courses.published(org.admin(), org.id());
        UUID cohortId = fx.cohort(org, "L" + UUID.randomUUID().toString().substring(0, 4));
        TestUser instructor = fx.member(org, "INSTRUCTOR");
        TestUser learner = fx.member(org, "LEARNER");
        TestUser otherLearner = fx.member(org, "LEARNER");
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\",\"%s\"]}".formatted(learner.id(), otherLearner.id())).andExpect(status().isOk());
        String offering = json(fx.send(post("/api/orgs/{orgId}/offerings", org.id()), org.admin(),
            "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(course.courseId(), cohortId)));
        UUID offeringId = UUID.fromString(JsonPath.read(offering, "$.id"));
        fx.send(post("/api/orgs/{orgId}/offerings/{id}/open", org.id(), offeringId), org.admin(), null).andExpect(status().isOk());
        return new Setup(org, course, cohortId, offeringId, instructor, learner, otherLearner);
    }

    private ResultActions schedule(Setup s, TestUser as, String extra, Instant start, Duration length) throws Exception {
        return fx.send(post("/api/orgs/{orgId}/live-sessions", s.org().id()), as,
            "{\"offeringId\":\"%s\",\"title\":\"Sorting live\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"%s}"
                .formatted(s.offeringId(), start, start.plus(length), extra));
    }

    private UUID scheduled(Setup s, Instant start) throws Exception {
        return UUID.fromString(JsonPath.read(json(schedule(s, s.instructor(), "", start, Duration.ofHours(1))
            .andExpect(status().isCreated())), "$.id"));
    }

    private ResultActions join(TestUser as, UUID sessionId) throws Exception {
        return fx.send(post("/api/live-sessions/{id}/join", sessionId), as, null);
    }

    private String channelRef(UUID sessionId) {
        return jdbc.sql("SELECT c.channel_ref FROM live_channel c JOIN live_session s ON s.channel_id = c.id WHERE s.id = :id")
            .param("id", sessionId).query(String.class).single();
    }

    private void streamStart(UUID sessionId) throws Exception {
        mockMvc.perform(post("/dev-live/channels/{ref}/stream-start", channelRef(sessionId))).andExpect(status().isNoContent());
    }

    private String sessionStatus(UUID sessionId) {
        return jdbc.sql("SELECT status FROM live_session WHERE id = :id").param("id", sessionId).query(String.class).single();
    }

    private static String decodedPayload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.substring(0, token.lastIndexOf('.'))), StandardCharsets.UTF_8);
    }

    @Test
    void aClassRunsFromScheduleToSettledAttendance() throws Exception {
        Setup s = setup();
        UUID sessionId = scheduled(s, Instant.now().plus(5, ChronoUnit.MINUTES));

        fx.send(get("/api/me/live-sessions"), s.learner(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(sessionId.toString())));

        fx.send(get("/api/orgs/{orgId}/live-sessions/{id}/ingest", s.org().id(), sessionId), s.instructor(), null)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.streamKey", startsWith("sk_")))
            .andExpect(jsonPath("$.ingestEndpoint", startsWith("rtmps://")));
        assertThat(sessionStatus(sessionId)).isEqualTo("READY");

        String joined = json(join(s.learner(), sessionId).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("VIEWER"))
            .andExpect(jsonPath("$.heartbeatIntervalSeconds").value(30)));
        assertThat(decodedPayload(JsonPath.read(joined, "$.chatToken"))).contains("[SEND_MESSAGE]");
        String playback = JsonPath.read(joined, "$.playbackUrl") + "?token=" + JsonPath.read(joined, "$.playbackToken");

        streamStart(sessionId);
        assertThat(sessionStatus(sessionId)).isEqualTo("LIVE");
        mockMvc.perform(get(URI.create(playback))).andExpect(status().isOk());
        mockMvc.perform(get(URI.create(JsonPath.<String>read(joined, "$.playbackUrl")))).andExpect(status().isForbidden());

        // 40 s of a 50 s class watched: PRESENT (75% threshold). Time is moved back rather than slept through.
        jdbc.sql("UPDATE live_session SET actual_start = now() - interval '50 seconds' WHERE id = :id").param("id", sessionId).update();
        jdbc.sql("UPDATE live_attendance SET last_seen_at = now() - interval '40 seconds' WHERE live_session_id = :id")
            .param("id", sessionId).update();
        fx.send(post("/api/live-sessions/{id}/heartbeat", sessionId), s.learner(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LIVE"));

        fx.send(post("/api/orgs/{orgId}/live-sessions/{id}/end", s.org().id(), sessionId), s.instructor(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        mockMvc.perform(get(URI.create(playback))).andExpect(status().isNotFound());

        String attendance = json(fx.send(get("/api/orgs/{orgId}/live-sessions/{id}/attendance", s.org().id(), sessionId),
            s.org().admin(), null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(attendance, "$[?(@.userId == '%s')].status".formatted(s.learner().id())))
            .containsExactly("PRESENT");
        assertThat(JsonPath.<List<Integer>>read(attendance, "$[?(@.userId == '%s')].attendedSeconds".formatted(s.learner().id())).getFirst())
            .isBetween(39, 41);
        assertThat(JsonPath.<List<String>>read(attendance, "$[?(@.userId == '%s')].status".formatted(s.otherLearner().id())))
            .containsExactly("ABSENT");

        join(s.learner(), sessionId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("session_ended"));
    }

    @Test
    void joiningFollowsTheSameAccessRulesAsRecordedLessons() throws Exception {
        Setup s = setup();
        UUID soon = scheduled(s, Instant.now().plus(5, ChronoUnit.MINUTES));
        UUID later = scheduled(s, Instant.now().plus(3, ChronoUnit.HOURS));

        join(users.user(), soon).andExpect(status().isNotFound());
        String moderator = json(join(s.org().admin(), soon).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MODERATOR")).andExpect(jsonPath("$.heartbeatIntervalSeconds").doesNotExist()));
        assertThat(decodedPayload(JsonPath.read(moderator, "$.chatToken"))).contains("DELETE_MESSAGE", "DISCONNECT_USER");

        join(s.learner(), later).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("session_not_open"))
            .andExpect(jsonPath("$.opensAt").exists());
        join(s.instructor(), later).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("HOST"));

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), s.learner().id()),
            s.org().admin(), null).andExpect(status().isNoContent());
        join(s.learner(), soon).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("access_expired"));
        fx.send(get("/api/me/live-sessions"), s.learner(), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        fx.send(post("/api/live-sessions/{id}/heartbeat", soon), s.otherLearner(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_joined"));
    }

    @Test
    void hostsAndCohortsCantBeDoubleBooked() throws Exception {
        Setup s = setup();
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID first = scheduled(s, start);

        schedule(s, s.instructor(), "", start.plus(30, ChronoUnit.MINUTES), Duration.ofHours(1))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("host_double_booked"));
        TestUser colleague = fx.member(s.org(), "INSTRUCTOR");
        schedule(s, colleague, "", start.plus(30, ChronoUnit.MINUTES), Duration.ofHours(1))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cohort_double_booked"));
        // Back-to-back is fine.
        schedule(s, s.instructor(), "", start.plus(1, ChronoUnit.HOURS), Duration.ofHours(1)).andExpect(status().isCreated());

        fx.send(post("/api/orgs/{orgId}/live-sessions/{id}/cancel", s.org().id(), first), s.instructor(), "{\"reason\":\"Holiday\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        schedule(s, colleague, "", start, Duration.ofMinutes(45)).andExpect(status().isCreated());
        join(s.learner(), first).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("session_cancelled"));
    }

    @Test
    void onlyStaffScheduleAndInstructorsOnlyRunTheirOwn() throws Exception {
        Setup s = setup();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);

        schedule(s, s.learner(), "", start, Duration.ofHours(1)).andExpect(status().isForbidden());
        TestUser colleague = fx.member(s.org(), "INSTRUCTOR");
        schedule(s, s.instructor(), ",\"hostUserId\":\"%s\"".formatted(colleague.id()), start, Duration.ofHours(1))
            .andExpect(status().isForbidden());
        schedule(s, s.org().admin(), ",\"hostUserId\":\"%s\"".formatted(s.learner().id()), start, Duration.ofHours(1))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid_host"));
        schedule(s, s.instructor(), "", Instant.now().minus(1, ChronoUnit.HOURS), Duration.ofMinutes(30))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("start_in_past"));
        schedule(s, s.instructor(), "", start, Duration.ofHours(7))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("session_too_long"));
        schedule(s, s.instructor(), ",\"lessonId\":\"%s\"".formatted(UUID.randomUUID()), start, Duration.ofHours(1))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("lesson_not_in_course"));

        Org otherOrg = fx.orgWithAdmin();
        fx.send(post("/api/orgs/{orgId}/live-sessions", otherOrg.id()), otherOrg.admin(),
            "{\"offeringId\":\"%s\",\"title\":\"x\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"}"
                .formatted(s.offeringId(), start, start.plus(1, ChronoUnit.HOURS)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("offering_not_found"));

        // The admin schedules for the colleague, tied to a lesson of the course.
        UUID sessionId = UUID.fromString(JsonPath.read(json(schedule(s, s.org().admin(),
            ",\"hostUserId\":\"%s\",\"lessonId\":\"%s\"".formatted(colleague.id(), s.course().lockedLessonId()), start,
            Duration.ofHours(1)).andExpect(status().isCreated())), "$.id"));
        fx.send(patch("/api/orgs/{orgId}/live-sessions/{id}", s.org().id(), sessionId), s.instructor(), "{\"title\":\"Mine now\"}")
            .andExpect(status().isForbidden());
        fx.send(get("/api/orgs/{orgId}/live-sessions/{id}/ingest", s.org().id(), sessionId), s.instructor(), null)
            .andExpect(status().isForbidden());
        fx.send(patch("/api/orgs/{orgId}/live-sessions/{id}", s.org().id(), sessionId), colleague,
                "{\"title\":\"Graphs live\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"}"
                    .formatted(start.plus(1, ChronoUnit.HOURS), start.plus(2, ChronoUnit.HOURS)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Graphs live"));
        fx.send(get("/api/orgs/{orgId}/live-sessions?cohortId={c}", s.org().id(), s.cohortId()), s.instructor(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(sessionId.toString())));
        fx.send(get("/api/orgs/{orgId}/live-sessions", s.org().id()), s.learner(), null).andExpect(status().isForbidden());
    }

    @Test
    void streamDropsAreToleratedButAbandonedClassesEnd() throws Exception {
        Setup s = setup();
        UUID sessionId = scheduled(s, Instant.now().plus(5, ChronoUnit.MINUTES));
        join(s.learner(), sessionId).andExpect(status().isOk());

        // A stream outside any session's slot isn't accepted (the local provider then stops it).
        UUID tomorrow = scheduled(s, Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(channelRef(tomorrow)).isEqualTo(channelRef(sessionId));
        assertThat(streamEvents.streamStarted(channelRef(sessionId), Instant.now().minus(1, ChronoUnit.HOURS))).isFalse();

        streamStart(sessionId);
        mockMvc.perform(post("/dev-live/channels/{ref}/stream-end", channelRef(sessionId))).andExpect(status().isNoContent());
        assertThat(streamEvents.sweep(Instant.now().plus(5, ChronoUnit.MINUTES))).isZero();
        assertThat(sessionStatus(sessionId)).isEqualTo("LIVE");

        // Offline time isn't credited.
        jdbc.sql("UPDATE live_attendance SET last_seen_at = now() - interval '20 seconds' WHERE live_session_id = :id")
            .param("id", sessionId).update();
        fx.send(post("/api/live-sessions/{id}/heartbeat", sessionId), s.learner(), null).andExpect(jsonPath("$.attendedSeconds").value(0));

        streamStart(sessionId); // reconnect
        mockMvc.perform(post("/dev-live/channels/{ref}/stream-end", channelRef(sessionId))).andExpect(status().isNoContent());
        Instant offlineSince = jdbc.sql("SELECT stream_offline_since FROM live_session WHERE id = :id").param("id", sessionId)
            .query(Timestamp.class).single().toInstant();
        assertThat(streamEvents.sweep(Instant.now().plus(11, ChronoUnit.MINUTES))).isEqualTo(1);
        assertThat(sessionStatus(sessionId)).isEqualTo("ENDED");
        assertThat(jdbc.sql("SELECT actual_end FROM live_session WHERE id = :id").param("id", sessionId)
            .query(Timestamp.class).single().toInstant()).isEqualTo(offlineSince);
        assertThat(jdbc.sql("SELECT attendance_status FROM live_attendance WHERE live_session_id = :id AND user_id = :u")
            .param("id", sessionId).param("u", s.learner().id()).query(String.class).single()).isEqualTo("ABSENT");
    }

    @Test
    void settledAttendanceCanBeCorrectedByStaff() throws Exception {
        Setup s = setup();
        UUID sessionId = scheduled(s, Instant.now().plus(5, ChronoUnit.MINUTES));
        String path = "/api/orgs/{orgId}/live-sessions/{id}/attendance/{userId}";

        fx.send(put(path, s.org().id(), sessionId, s.otherLearner().id()), s.org().admin(), "{\"status\":\"PRESENT\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("session_not_ended"));
        fx.send(post("/api/orgs/{orgId}/live-sessions/{id}/end", s.org().id(), sessionId), s.instructor(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("session_not_live"));

        streamStart(sessionId);
        fx.send(post("/api/orgs/{orgId}/live-sessions/{id}/end", s.org().id(), sessionId), s.instructor(), null).andExpect(status().isOk());

        fx.send(put(path, s.org().id(), sessionId, s.otherLearner().id()), s.instructor(),
                "{\"status\":\"PRESENT\",\"note\":\"Watched from the lab projector\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PRESENT"))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.email").value(s.otherLearner().email()));
        fx.send(put(path, s.org().id(), sessionId, UUID.randomUUID()), s.instructor(), "{\"status\":\"PRESENT\"}")
            .andExpect(status().isNotFound());
        fx.send(put(path, s.org().id(), sessionId, s.otherLearner().id()), s.learner(), "{\"status\":\"ABSENT\"}")
            .andExpect(status().isForbidden());
    }
}
