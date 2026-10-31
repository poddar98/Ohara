package org.example.ohara.ai.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.ohara.content.application.VideoEvents;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.AssetFixtures;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TranscriptIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;

    TestUsers users;
    OrgFixtures fx;
    AssetFixtures assets;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        assets = new AssetFixtures(mockMvc, fx);
    }

    /** A published college course whose one lesson holds a video (with optional STT marker); one learner enrolled. */
    private record Setup(Org org, String lessonId, String contentId, UUID assetId, UUID cohortId, TestUser learner, String enrollmentId) {
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private Setup setup(String marker) throws Exception {
        Org org = fx.orgWithAdmin();
        UUID assetId = assets.upload(org.admin(), org.id(), "VIDEO", "video/mp4",
            ("....ftypisom lecture " + marker).getBytes(StandardCharsets.US_ASCII), "");
        String course = json(fx.send(post("/api/courses"), org.admin(), "{\"title\":\"Algorithms\",\"slug\":\"a-%s\",\"ownerOrganizationId\":\"%s\"}"
            .formatted(UUID.randomUUID().toString().substring(0, 10), org.id())));
        String base = "/api/courses/" + JsonPath.read(course, "$.id") + "/versions/" + JsonPath.read(course, "$.draftVersionId");
        fx.send(patch(base), org.admin(), "{\"description\":\"d\"}");
        String moduleId = JsonPath.read(json(fx.send(post(base + "/modules"), org.admin(), "{\"title\":\"M\"}")), "$.modules[0].id");
        String lessonId = JsonPath.read(json(fx.send(post(base + "/modules/" + moduleId + "/lessons"), org.admin(),
            "{\"title\":\"Sorting\",\"type\":\"VIDEO\",\"durationSeconds\":30}")), "$.modules[0].lessons[0].id");
        String contentId = JsonPath.read(json(fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(),
            "{\"kind\":\"VIDEO\",\"contentAssetId\":\"%s\"}".formatted(assetId))), "$.modules[0].lessons[0].contents[0].id");
        jobs.drain(); // video READY (and transcribed) before publishing
        fx.send(post(base + "/publish"), org.admin(), "{}").andExpect(status().isOk());

        UUID cohortId = fx.cohort(org, "T" + UUID.randomUUID().toString().substring(0, 4));
        TestUser learner = fx.member(org, "LEARNER");
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());
        String offering = json(fx.send(post("/api/orgs/{orgId}/offerings", org.id()), org.admin(),
            "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(JsonPath.read(course, "$.id"), cohortId)));
        fx.send(post("/api/orgs/{orgId}/offerings/{id}/open", org.id(), (String) JsonPath.read(offering, "$.id")), org.admin(), null)
            .andExpect(status().isOk());
        String enrollmentId = JsonPath.read(json(fx.send(get("/api/me/courses"), learner, null)), "$[0].enrollmentId");
        return new Setup(org, lessonId, contentId, assetId, cohortId, learner, enrollmentId);
    }

    private ResultActions transcript(TestUser as, Setup s) throws Exception {
        return fx.send(get("/api/lessons/{l}/contents/{c}/transcript", s.lessonId(), s.contentId()), as, null);
    }

    private ResultActions search(TestUser as, Setup s, String q) throws Exception {
        return fx.send(get("/api/enrollments/{id}/transcripts/search?q={q}", s.enrollmentId(), q), as, null);
    }

    private String aiStatus(Setup s) throws Exception {
        return json(fx.send(get("/api/assets/{id}/ai", s.assetId()), s.org().admin(), null).andExpect(status().isOk()));
    }

    private String artifactId(String status, String type) {
        return JsonPath.<List<String>>read(status, "$.artifacts[?(@.type == '%s')].id".formatted(type)).getFirst();
    }

    private long transcriptionRuns(UUID assetId) {
        return jdbc.sql("SELECT count(*) FROM transcription WHERE content_asset_id = :a").param("a", assetId).query(Long.class).single();
    }

    @Test
    void aReadyVideoIsTranscribedCaptionedAndSearchable() throws Exception {
        Setup s = setup("");

        String status = aiStatus(s);
        assertThat(JsonPath.<String>read(status, "$.transcription.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Number>read(status, "$.transcription.costMicroUsd").longValue()).isPositive();
        assertThat(JsonPath.<List<String>>read(status, "$.artifacts[*].status")).containsOnly("APPROVED").hasSize(2);

        transcript(s.learner(), s).andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("en-IN"))
            .andExpect(jsonPath("$.segments.length()", greaterThan(0)))
            .andExpect(jsonPath("$.segments[0].text", containsString("sorting")));

        MvcResult playback = fx.send(post("/api/playback/sessions"), s.learner(),
            "{\"lessonId\":\"%s\",\"contentId\":\"%s\"}".formatted(s.lessonId(), s.contentId())).andExpect(status().isOk()).andReturn();
        String captionsUrl = JsonPath.read(playback.getResponse().getContentAsString(), "$.captions[0].url");
        Cookie cookie = playback.getResponse().getCookie("OHARA-DEV-CDN");
        mockMvc.perform(get(URI.create(captionsUrl)).cookie(cookie)).andExpect(status().isOk())
            .andExpect(content().string(startsWith("WEBVTT")))
            .andExpect(content().string(containsString("00:00:00.000 --> 00:00:10.000")));

        search(s.learner(), s, "merge sort").andExpect(status().isOk())
            .andExpect(jsonPath("$[0].lessonId").value(s.lessonId()))
            .andExpect(jsonPath("$[0].contentId").value(s.contentId()))
            .andExpect(jsonPath("$[0].startMs").value(10_000))
            .andExpect(jsonPath("$[0].snippet", containsString("<b>")));
        search(s.learner(), s, "photosynthesis").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        search(users.user(), s, "merge sort").andExpect(status().isNotFound());
        transcript(users.user(), s).andExpect(status().isNotFound());
    }

    @Test
    void theSameSourceIsNeverTranscribedTwiceUnlessForced() throws Exception {
        Setup s = setup("");
        assertThat(transcriptionRuns(s.assetId())).isEqualTo(1);

        tx.executeWithoutResult(t -> events.publishEvent(new VideoEvents.Ready(s.assetId()))); // a replayed READY event
        jobs.drain();
        assertThat(transcriptionRuns(s.assetId())).isEqualTo(1);
        fx.send(post("/api/assets/{id}/ai/transcribe", s.assetId()), s.org().admin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_transcribed"));
        fx.send(post("/api/assets/{id}/ai/transcribe", s.assetId()), s.learner(), null).andExpect(status().isNotFound());

        fx.send(post("/api/assets/{id}/ai/transcribe?force=true", s.assetId()), s.org().admin(), null).andExpect(status().isAccepted());
        jobs.drain();
        assertThat(transcriptionRuns(s.assetId())).isEqualTo(2);
        String status = aiStatus(s);
        assertThat(JsonPath.<List<String>>read(status, "$.artifacts[?(@.type == 'TRANSCRIPT')].status"))
            .containsExactly("APPROVED", "SUPERSEDED");
        transcript(s.learner(), s).andExpect(jsonPath("$.transcriptVersion").value(2));
    }

    @Test
    void transcriptionFailuresNeverAffectPlayback() throws Exception {
        Setup s = setup("FAIL_STT");

        String status = aiStatus(s);
        assertThat(JsonPath.<String>read(status, "$.transcription.status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(status, "$.transcription.failureCode")).isEqualTo("stt_failed");
        assertThat(JsonPath.<Integer>read(status, "$.transcription.attempts")).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(status, "$.artifacts")).isEmpty();

        fx.send(post("/api/playback/sessions"), s.learner(), "{\"lessonId\":\"%s\",\"contentId\":\"%s\"}".formatted(s.lessonId(), s.contentId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.captions.length()").value(0));
        transcript(s.learner(), s).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("transcript_not_available"));

        // Staff may try again (here it fails again: the marker is in the file).
        fx.send(post("/api/assets/{id}/ai/transcribe", s.assetId()), s.org().admin(), null).andExpect(status().isAccepted());
        jobs.drain();
        assertThat(transcriptionRuns(s.assetId())).isEqualTo(2);
    }

    @Test
    void lowConfidenceTranscriptsWaitForReview() throws Exception {
        Setup s = setup("MUMBLE");

        String status = aiStatus(s);
        assertThat(JsonPath.<List<String>>read(status, "$.artifacts[*].status")).containsOnly("GENERATED");
        transcript(s.learner(), s).andExpect(status().isNotFound());
        fx.send(get("/api/assets/{id}/transcript", s.assetId()), s.org().admin(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GENERATED"));
        search(s.learner(), s, "merge sort").andExpect(jsonPath("$.length()").value(0));

        String transcriptId = artifactId(status, "TRANSCRIPT");
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), transcriptId), s.learner(), null).andExpect(status().isNotFound());
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), transcriptId), s.org().admin(), "{\"note\":\"Checked\"}")
            .andExpect(status().isOk());
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), transcriptId), s.org().admin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("artifact_not_reviewable"));
        transcript(s.learner(), s).andExpect(status().isOk());
        search(s.learner(), s, "merge sort").andExpect(jsonPath("$.length()", greaterThan(0)));

        fx.send(post("/api/assets/{a}/artifacts/{id}/reject", s.assetId(), transcriptId), s.org().admin(), "{\"note\":\"Wrong terms\"}")
            .andExpect(status().isOk());
        transcript(s.learner(), s).andExpect(status().isNotFound());
    }

    @Test
    void searchOnlyCoversVideosTheLearnerMayOpen() throws Exception {
        Setup s = setup("");
        Setup other = setup(""); // another college, the same lecture words

        assertThat(JsonPath.<List<String>>read(json(search(s.learner(), s, "quick sort pivot")), "$[*].assetId"))
            .containsOnly(s.assetId().toString());

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), s.learner().id()),
            s.org().admin(), null).andExpect(status().isNoContent());
        search(s.learner(), s, "quick sort pivot").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        search(other.learner(), other, "quick sort pivot").andExpect(jsonPath("$[0].assetId").value(other.assetId().toString()));
    }
}
