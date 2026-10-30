package org.example.ohara.media.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlaybackIntegrationTest {

    static final byte[] MP4 = "....ftypisom lecture".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;

    TestUsers users;
    OrgFixtures fx;
    AssetFixtures assets;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
        assets = new AssetFixtures(mockMvc, fx);
    }

    /** A published college course whose only lesson holds a READY video; one learner enrolled via a cohort. */
    private record Setup(Org org, String lessonId, String contentId, UUID assetId, UUID cohortId, TestUser learner) {
    }

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private Setup setup() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID assetId = assets.upload(org.admin(), org.id(), "VIDEO", "video/mp4", MP4, "");
        jobs.drain();

        String course = json(fx.send(post("/api/courses"), org.admin(), "{\"title\":\"Video course\",\"slug\":\"v-%s\",\"ownerOrganizationId\":\"%s\"}"
            .formatted(UUID.randomUUID().toString().substring(0, 10), org.id())));
        String base = "/api/courses/" + JsonPath.read(course, "$.id") + "/versions/" + JsonPath.read(course, "$.draftVersionId");
        fx.send(patch(base), org.admin(), "{\"description\":\"d\"}");
        String moduleId = JsonPath.read(json(fx.send(post(base + "/modules"), org.admin(), "{\"title\":\"M\"}")), "$.modules[0].id");
        String lessonId = JsonPath.read(json(fx.send(post(base + "/modules/" + moduleId + "/lessons"), org.admin(),
            "{\"title\":\"Lecture 1\",\"type\":\"VIDEO\",\"durationSeconds\":30}")), "$.modules[0].lessons[0].id");
        String tree = json(fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(),
            "{\"kind\":\"VIDEO\",\"contentAssetId\":\"%s\"}".formatted(assetId)).andExpect(status().isCreated()));
        String contentId = JsonPath.read(tree, "$.modules[0].lessons[0].contents[0].id");
        fx.send(post(base + "/publish"), org.admin(), "{}").andExpect(status().isOk());

        UUID cohortId = fx.cohort(org, "V" + UUID.randomUUID().toString().substring(0, 4));
        TestUser learner = fx.member(org, "LEARNER");
        fx.send(post("/api/orgs/{orgId}/cohorts/{cohortId}/members", org.id(), cohortId), org.admin(),
            "{\"userIds\":[\"%s\"]}".formatted(learner.id())).andExpect(status().isOk());
        String offering = json(fx.send(post("/api/orgs/{orgId}/offerings", org.id()), org.admin(),
            "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(JsonPath.read(course, "$.id"), cohortId)));
        fx.send(post("/api/orgs/{orgId}/offerings/{id}/open", org.id(), (String) JsonPath.read(offering, "$.id")), org.admin(), null)
            .andExpect(status().isOk());
        return new Setup(org, lessonId, contentId, assetId, cohortId, learner);
    }

    private ResultActions start(TestUser as, Setup s) throws Exception {
        return fx.send(post("/api/playback/sessions"), as, "{\"lessonId\":\"%s\",\"contentId\":\"%s\"}".formatted(s.lessonId(), s.contentId()));
    }

    private static Cookie cdnCookie(MockHttpServletResponse response) {
        return response.getCookie("OHARA-DEV-CDN");
    }

    private ResultActions fetch(String url, Cookie cookie) throws Exception {
        var request = get(URI.create(url));
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    @Test
    void enrolledLearnerStreamsThroughSignedCookies() throws Exception {
        Setup s = setup();

        MvcResult started = start(s.learner(), s).andExpect(status().isOk())
            .andExpect(jsonPath("$.masterPlaylistUrl", containsString("/dev-cdn/delivery/org/")))
            .andExpect(jsonPath("$.lessonKey").exists())
            .andExpect(jsonPath("$.enrollmentId").exists())
            .andReturn();
        Cookie cookie = cdnCookie(started.getResponse());
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        String master = JsonPath.read(started.getResponse().getContentAsString(), "$.masterPlaylistUrl");
        Instant expiresAt = Instant.parse(JsonPath.read(started.getResponse().getContentAsString(), "$.expiresAt"));
        assertThat(expiresAt).isBetween(Instant.now().plus(19, ChronoUnit.MINUTES), Instant.now().plus(21, ChronoUnit.MINUTES));

        String variantBase = master.substring(0, master.lastIndexOf('/') + 1) + "720p/";
        fetch(master, cookie).andExpect(status().isOk()).andExpect(content().string(containsString("720p/index.m3u8")));
        fetch(variantBase + "index.m3u8", cookie).andExpect(status().isOk()).andExpect(content().string(containsString("#EXT-X-ENDLIST")));
        fetch(variantBase + "seg_00001.ts", cookie).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("immutable")));

        // No cookie, or a forged one: refused at the edge.
        fetch(master, null).andExpect(status().isForbidden());
        fetch(master, new Cookie("OHARA-DEV-CDN", cookie.getValue() + "x")).andExpect(status().isForbidden());
    }

    @Test
    void aCookieOnlyUnlocksItsOwnVideo() throws Exception {
        Setup s = setup();
        UUID otherVideo = assets.upload(s.org().admin(), s.org().id(), "VIDEO", "video/mp4", MP4, "");
        jobs.drain();
        String otherMaster = jdbc.sql("SELECT master_playlist_key FROM content_asset WHERE id = :id").param("id", otherVideo)
            .query(String.class).single();

        Cookie cookie = cdnCookie(start(s.learner(), s).andReturn().getResponse());

        fetch("http://localhost:8080/dev-cdn/" + otherMaster, cookie).andExpect(status().isForbidden());
    }

    @Test
    void refreshReChecksAccessSoRevocationStopsPlayback() throws Exception {
        Setup s = setup();
        String sessionId = JsonPath.read(json(start(s.learner(), s)), "$.sessionId");

        fx.send(post("/api/playback/sessions/{id}/refresh", sessionId), s.learner(), null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(sessionId));
        fx.send(post("/api/playback/sessions/{id}/refresh", sessionId), users.user(), null).andExpect(status().isNotFound());

        fx.send(delete("/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}", s.org().id(), s.cohortId(), s.learner().id()),
            s.org().admin(), null).andExpect(status().isNoContent());

        fx.send(post("/api/playback/sessions/{id}/refresh", sessionId), s.learner(), null)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("access_expired"));
        start(s.learner(), s).andExpect(status().isForbidden());
    }

    @Test
    void sessionsNeverOutliveTheLearnersAccess() throws Exception {
        Setup s = setup();
        Instant soon = Instant.now().plus(3, ChronoUnit.MINUTES);
        jdbc.sql("UPDATE access_grant SET valid_until = :until WHERE user_id = :u")
            .param("until", java.sql.Timestamp.from(soon)).param("u", s.learner().id()).update();

        String started = json(start(s.learner(), s).andExpect(status().isOk()));

        assertThat(Instant.parse(JsonPath.read(started, "$.expiresAt"))).isBeforeOrEqualTo(soon.plusSeconds(1));
    }

    @Test
    void outsidersCantStartAndStaffCan() throws Exception {
        Setup s = setup();

        start(users.user(), s).andExpect(status().isNotFound());
        start(s.org().admin(), s).andExpect(status().isOk()).andExpect(jsonPath("$.enrollmentId").doesNotExist());
        fx.send(post("/api/playback/sessions"), s.learner(), "{\"lessonId\":\"%s\",\"contentId\":\"%s\"}".formatted(s.lessonId(), UUID.randomUUID()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("content_not_found"));
    }

    @Test
    void retiredVideosCantBePlayed() throws Exception {
        Setup s = setup();
        fx.send(post("/api/assets/{id}/retire", s.assetId()), s.org().admin(), null).andExpect(status().isOk());

        start(s.learner(), s).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("media_not_ready"));
    }
}
