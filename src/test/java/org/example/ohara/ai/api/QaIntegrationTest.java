package org.example.ohara.ai.api;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QaIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;

    TestUsers users;
    OrgFixtures fx;
    LectureFixtures lectures;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        lectures = new LectureFixtures(fx, new AssetFixtures(mockMvc, fx), jobs);
    }

    private ResultActions ask(TestUser as, Lecture l, String question) throws Exception {
        return fx.send(post("/api/enrollments/{id}/qa", l.enrollmentId()), as, "{\"question\":\"%s\"}".formatted(question));
    }

    private Map<String, Object> logRow(String questionId) {
        return jdbc.sql("SELECT * FROM ai_qa_log WHERE id = :id").param("id", UUID.fromString(questionId)).query().singleRow();
    }

    @Test
    void answersAreGroundedInTheLearnersLecturesWithDeepLinks() throws Exception {
        Lecture l = lectures.create("");

        String body = ask(l.learner(), l, "What does merge sort do with the array?").andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("ANSWERED"))
            .andExpect(jsonPath("$.grounded").value(true))
            .andExpect(jsonPath("$.answer", containsString("Merge sort")))
            .andExpect(jsonPath("$.citations[0].lessonId").value(l.lessonId()))
            .andExpect(jsonPath("$.citations[0].contentId").value(l.contentId()))
            .andExpect(jsonPath("$.citations[0].assetId").value(l.assetId().toString()))
            .andExpect(jsonPath("$.citations[0].endMs", greaterThan(10_000)))
            .andReturn().getResponse().getContentAsString();

        String questionId = JsonPath.read(body, "$.questionId");
        Map<String, Object> row = logRow(questionId);
        assertThat(row.get("outcome")).isEqualTo("ANSWERED");
        assertThat(row.get("grounded")).isEqualTo(true);
        assertThat(((Number) row.get("citation_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("cost_micro_usd")).longValue()).isPositive();
        assertThat(row.get("question_sha256").toString()).hasSize(64).doesNotContain("merge");

        fx.send(post("/api/qa/{id}/feedback", questionId), l.learner(), "{\"rating\":\"UP\"}").andExpect(status().isNoContent());
        fx.send(post("/api/qa/{id}/feedback", questionId), users.user(), "{\"rating\":\"DOWN\"}").andExpect(status().isNotFound());
        assertThat(((Number) logRow(questionId).get("feedback")).intValue()).isEqualTo(1);
    }

    @Test
    void uncoveredQuestionsAreAnsweredWithoutCallingTheModel() throws Exception {
        Lecture l = lectures.create("");

        String body = ask(l.learner(), l, "Explain photosynthesis in plants").andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("NOT_COVERED"))
            .andExpect(jsonPath("$.grounded").value(false))
            .andExpect(jsonPath("$.citations.length()").value(0))
            .andReturn().getResponse().getContentAsString();
        Map<String, Object> row = logRow(JsonPath.read(body, "$.questionId"));
        assertThat(row.get("model")).isNull();
        assertThat(((Number) row.get("input_tokens")).intValue()).isZero();
    }

    @Test
    void citationsAreCheckedAndModelFailuresAreNeverShownAsAnswers() throws Exception {
        Lecture l = lectures.create("");

        String badCite = ask(l.learner(), l, "merge sort LLM_BADCITE").andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("UNSUPPORTED"))
            .andExpect(jsonPath("$.grounded").value(false))
            .andExpect(jsonPath("$.answer", containsString("couldn't find support")))
            .andExpect(jsonPath("$.citations.length()").value(0))
            .andReturn().getResponse().getContentAsString();
        assertThat(((Number) logRow(JsonPath.read(badCite, "$.questionId")).get("dropped_citation_count")).intValue()).isEqualTo(1);

        ask(l.learner(), l, "merge sort LLM_REFUSE").andExpect(jsonPath("$.outcome").value("REFUSED"));
        ask(l.learner(), l, "merge sort LLM_BROKEN").andExpect(jsonPath("$.outcome").value("INVALID"))
            .andExpect(jsonPath("$.grounded").value(false));
        ask(l.learner(), l, "merge sort LLM_DOWN")
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ai_unavailable"));
    }

    @Test
    void retrievalNeverLeavesTheLearnersOwnOpenVideos() throws Exception {
        Lecture mine = lectures.create("");
        Lecture theirs = lectures.create(""); // another college, the same lecture

        String body = ask(mine.learner(), mine, "How does quick sort pick a pivot?")
            .andExpect(jsonPath("$.outcome").value("ANSWERED")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.citations[*].assetId")).containsOnly(mine.assetId().toString());

        ask(theirs.learner(), mine, "How does quick sort pick a pivot?").andExpect(status().isNotFound());
        fx.send(post("/api/enrollments/{id}/qa", mine.enrollmentId()), mine.learner(),
                "{\"question\":\"quick sort pivot\",\"lessonId\":\"%s\"}".formatted(UUID.randomUUID()))
            .andExpect(jsonPath("$.outcome").value("NOT_COVERED"));

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", mine.org().id(), mine.cohortId(), mine.learner().id()),
            mine.org().admin(), null).andExpect(status().isNoContent());
        ask(mine.learner(), mine, "How does quick sort pick a pivot?").andExpect(jsonPath("$.outcome").value("NOT_COVERED"));
    }

    @Test
    void eachLearnerHasADailyQuestionLimit() throws Exception {
        Lecture l = lectures.create("");
        for (int i = 0; i < 30; i++) {
            jdbc.sql("""
                    INSERT INTO ai_qa_log (id, user_id, enrollment_id, course_id, question_sha256, outcome)
                    VALUES (:id, :u, :e, :c, :sha, 'NOT_COVERED')""")
                .param("id", UUID.randomUUID()).param("u", l.learner().id()).param("e", UUID.fromString(l.enrollmentId()))
                .param("c", UUID.randomUUID()).param("sha", "0".repeat(64)).update();
        }
        ask(l.learner(), l, "merge sort").andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("qa_limit_reached")).andExpect(jsonPath("$.limit").value(30));
        jdbc.sql("UPDATE ai_qa_log SET created_at = now() - interval '25 hours' WHERE user_id = :u").param("u", l.learner().id()).update();
        ask(l.learner(), l, "merge sort").andExpect(status().isOk());
    }

    @Test
    void platformAdminsMeasureRetrievalAndGrounding() throws Exception {
        Lecture l = lectures.create("");
        String request = """
            {"withAnswers":true,"cases":[
              {"question":"Why does merge sort divide the array into halves?","assetId":"%1$s","expectedStartMs":10000,"expectedEndMs":20000},
              {"question":"What is photosynthesis?","assetId":"%1$s","expectedStartMs":0,"expectedEndMs":30000}]}"""
            .formatted(l.assetId());

        fx.send(post("/api/admin/ai/qa/evaluate"), l.learner(), request).andExpect(status().isForbidden());
        fx.send(post("/api/admin/ai/qa/evaluate"), fx.platformAdmin(), request).andExpect(status().isOk())
            .andExpect(jsonPath("$.cases").value(2))
            .andExpect(jsonPath("$.k").value(8))
            .andExpect(jsonPath("$.recallAtK").value(0.5))
            .andExpect(jsonPath("$.groundedRate").value(0.5))
            .andExpect(jsonPath("$.results[0].rank").value(1))
            .andExpect(jsonPath("$.results[1].hit").value(false))
            .andExpect(jsonPath("$.results[1].outcome").value("NOT_COVERED"));
    }
}
