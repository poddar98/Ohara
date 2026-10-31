package org.example.ohara.ai.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.ohara.content.application.VideoEvents;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.AssetFixtures;
import org.example.ohara.support.LectureFixtures;
import org.example.ohara.support.LectureFixtures.Lecture;
import org.example.ohara.support.OrgFixtures;
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
    LectureFixtures lectures;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        assets = new AssetFixtures(mockMvc, fx);
        lectures = new LectureFixtures(fx, assets, jobs);
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private ResultActions transcript(TestUser as, Lecture s) throws Exception {
        return fx.send(get("/api/lessons/{l}/contents/{c}/transcript", s.lessonId(), s.contentId()), as, null);
    }

    private ResultActions search(TestUser as, Lecture s, String q) throws Exception {
        return fx.send(get("/api/enrollments/{id}/transcripts/search?q={q}", s.enrollmentId(), q), as, null);
    }

    private String aiStatus(Lecture s) throws Exception {
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
        Lecture s = lectures.create("");

        String status = aiStatus(s);
        assertThat(JsonPath.<String>read(status, "$.transcription.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Number>read(status, "$.transcription.costMicroUsd").longValue()).isPositive();
        assertThat(JsonPath.<List<String>>read(status, "$.artifacts[?(@.type in ['TRANSCRIPT', 'CAPTIONS'])].status"))
            .containsOnly("APPROVED").hasSize(2);

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
        Lecture s = lectures.create("");
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
        Lecture s = lectures.create("FAIL_STT");

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
        Lecture s = lectures.create("MUMBLE");

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
        Lecture s = lectures.create("");
        Lecture other = lectures.create(""); // another college, the same lecture words

        assertThat(JsonPath.<List<String>>read(json(search(s.learner(), s, "quick sort pivot")), "$[*].assetId"))
            .containsOnly(s.assetId().toString());

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), s.learner().id()),
            s.org().admin(), null).andExpect(status().isNoContent());
        search(s.learner(), s, "quick sort pivot").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        search(other.learner(), other, "quick sort pivot").andExpect(jsonPath("$[0].assetId").value(other.assetId().toString()));
    }

    // --- LLM study material ---

    private ResultActions generate(TestUser as, Lecture s, String type, String instructions, boolean force) throws Exception {
        String extra = instructions == null ? "" : ",\"instructions\":\"%s\"".formatted(instructions);
        return fx.send(post("/api/assets/{id}/ai/generate", s.assetId()), as,
            "{\"type\":\"%s\",\"force\":%s%s}".formatted(type, force, extra));
    }

    private ResultActions study(TestUser as, Lecture s) throws Exception {
        return fx.send(get("/api/lessons/{l}/contents/{c}/study", s.lessonId(), s.contentId()), as, null);
    }

    private String generationField(String status, String task, String field) {
        List<Object> values = JsonPath.read(status, "$.generations[?(@.task == '%s')].%s".formatted(task, field));
        return values.isEmpty() ? null : String.valueOf(values.getFirst());
    }

    @Test
    void anApprovedTranscriptIsSummarizedIntoDraftsThatStaffReview() throws Exception {
        Lecture s = lectures.create("");

        String status = aiStatus(s);
        assertThat(generationField(status, "SUMMARY_CHAPTERS", "status")).isEqualTo("COMPLETED");
        assertThat(generationField(status, "SUMMARY_CHAPTERS", "promptVersion")).isEqualTo("summary-chapters/v1");
        assertThat(Long.parseLong(generationField(status, "SUMMARY_CHAPTERS", "costMicroUsd"))).isPositive();
        assertThat(JsonPath.<List<String>>read(status, "$.artifacts[?(@.type in ['SUMMARY', 'CHAPTERS'])].status"))
            .containsExactlyInAnyOrder("GENERATED", "GENERATED");
        study(s.learner(), s).andExpect(status().isOk())
            .andExpect(jsonPath("$.summary").doesNotExist()).andExpect(jsonPath("$.chapters.length()").value(0));

        String chaptersId = artifactId(status, "CHAPTERS");
        fx.send(patch("/api/assets/{a}/artifacts/{id}", s.assetId(), chaptersId), s.org().admin(),
                "{\"content\":{\"chapters\":[{\"startMs\":20000,\"title\":\"Quick sort\"},{\"startMs\":0,\"title\":\"Intro\"}]}}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("invalid_content"))
            .andExpect(jsonPath("$.problems[0]", containsString("time order")));
        fx.send(patch("/api/assets/{a}/artifacts/{id}", s.assetId(), chaptersId), s.org().admin(),
                "{\"content\":{\"chapters\":[{\"startMs\":0,\"title\":\"Intro\"},{\"startMs\":20000,\"title\":\"Quick sort\"}]}}")
            .andExpect(status().isOk());
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), chaptersId), s.org().admin(), null).andExpect(status().isOk());
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), artifactId(status, "SUMMARY")), s.org().admin(), null)
            .andExpect(status().isOk());

        study(s.learner(), s).andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.shortSummary", containsString("sorting")))
            .andExpect(jsonPath("$.chapters[1].title").value("Quick sort"))
            .andExpect(jsonPath("$.flashcards.length()").value(0));
        study(users.user(), s).andExpect(status().isNotFound());

        generate(s.org().admin(), s, "SUMMARY", null, false)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_generated"));
    }

    @Test
    void quizAndFlashcardDraftsAreMadeOnRequest() throws Exception {
        Lecture s = lectures.create("");

        generate(s.learner(), s, "QUIZ_DRAFT", null, false).andExpect(status().isNotFound());
        generate(s.org().admin(), s, "QUIZ_DRAFT", "Focus on complexity", false).andExpect(status().isAccepted());
        generate(s.org().admin(), s, "QUIZ_DRAFT", null, false)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("generation_in_progress"));
        generate(s.org().admin(), s, "FLASHCARDS", null, false).andExpect(status().isAccepted());
        jobs.drain();

        String status = aiStatus(s);
        String quiz = JsonPath.<List<String>>read(status, "$.artifacts[?(@.type == 'QUIZ_DRAFT')].content").getFirst();
        assertThat(JsonPath.<List<Object>>read(quiz, "$.questions")).isNotEmpty();
        assertThat(JsonPath.<Integer>read(quiz, "$.questions[0].options.length()")).isBetween(2, 6);
        assertThat(JsonPath.<Integer>read(quiz, "$.questions[0].sourceEndMs")).isPositive();

        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), artifactId(status, "FLASHCARDS")), s.org().admin(), null)
            .andExpect(status().isOk());
        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), artifactId(status, "QUIZ_DRAFT")), s.org().admin(), null)
            .andExpect(status().isOk());
        study(s.learner(), s).andExpect(jsonPath("$.flashcards.length()", greaterThan(0)))
            .andExpect(jsonPath("$.flashcards[0].back", containsString("sorting")))
            .andExpect(jsonPath("$.quiz").doesNotExist());
    }

    @Test
    void invalidAnswersGetOneRepairAndRefusalsOrBrokenAnswersFail() throws Exception {
        Lecture s = lectures.create("");

        generate(s.org().admin(), s, "FLASHCARDS", "LLM_INVALID", false).andExpect(status().isAccepted());
        generate(s.org().admin(), s, "QUIZ_DRAFT", "LLM_BROKEN", false).andExpect(status().isAccepted());
        generate(s.org().admin(), s, "SUMMARY", "LLM_REFUSE", true).andExpect(status().isAccepted());
        jobs.drain();

        String status = aiStatus(s);
        assertThat(generationField(status, "FLASHCARDS", "status")).isEqualTo("COMPLETED");
        assertThat(generationField(status, "QUIZ_DRAFT", "status")).isEqualTo("FAILED");
        assertThat(generationField(status, "QUIZ_DRAFT", "failureCode")).isEqualTo("output_invalid");
        assertThat(generationField(status, "SUMMARY_CHAPTERS", "failureCode")).isEqualTo("llm_refused");
        assertThat(JsonPath.<List<Object>>read(status, "$.artifacts[?(@.type == 'QUIZ_DRAFT')]")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(status, "$.artifacts[?(@.type == 'FLASHCARDS')]")).hasSize(1);
        // The failed summary run doesn't touch the earlier summary draft.
        assertThat(JsonPath.<List<Object>>read(status, "$.artifacts[?(@.type == 'SUMMARY')]")).hasSize(1);
    }

    @Test
    void nothingIsGeneratedFromAnUnapprovedTranscript() throws Exception {
        Lecture s = lectures.create("MUMBLE");

        assertThat(JsonPath.<List<Object>>read(aiStatus(s), "$.generations")).isEmpty();
        generate(s.org().admin(), s, "QUIZ_DRAFT", null, false)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("transcript_required"));

        fx.send(post("/api/assets/{a}/artifacts/{id}/approve", s.assetId(), artifactId(aiStatus(s), "TRANSCRIPT")), s.org().admin(), null)
            .andExpect(status().isOk());
        jobs.drain();
        assertThat(generationField(aiStatus(s), "SUMMARY_CHAPTERS", "status")).isEqualTo("COMPLETED");
    }
}
