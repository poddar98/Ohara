package org.example.ohara.support;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers.TestUser;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A published college course whose one lesson holds a processed (and transcribed) video, with one enrolled learner. */
public class LectureFixtures {

    public record Lecture(Org org, String lessonId, String contentId, UUID assetId, UUID cohortId, TestUser learner,
                          String enrollmentId) {
    }

    private final OrgFixtures fx;
    private final AssetFixtures assets;
    private final JobRunner jobs;

    public LectureFixtures(OrgFixtures fx, AssetFixtures assets, JobRunner jobs) {
        this.fx = fx;
        this.assets = assets;
        this.jobs = jobs;
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** {@code marker} goes into the video bytes (the local STT stand-in reacts to FAIL_STT, SILENT, MUMBLE). */
    public Lecture create(String marker) throws Exception {
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
        jobs.drain(); // video READY (and transcribed, summarized) before publishing
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
        return new Lecture(org, lessonId, contentId, assetId, cohortId, learner, enrollmentId);
    }
}
