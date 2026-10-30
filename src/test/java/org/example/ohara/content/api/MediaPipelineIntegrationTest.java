package org.example.ohara.content.api;

import org.example.ohara.content.application.ObjectStorage;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.example.ohara.platform.jobs.internal.JobRunner;
import org.example.ohara.support.AssetFixtures;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MediaPipelineIntegrationTest {

    static final byte[] MP4 = "....ftypisom lecture".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JobRunner jobs;
    @Autowired JobScheduler scheduler;
    @Autowired ObjectStorage storage;

    OrgFixtures fx;
    AssetFixtures assets;
    Org org;

    @BeforeEach
    void setUp() throws Exception {
        fx = new OrgFixtures(mockMvc, new TestUsers(jdbc, passwordEncoder, mockMvc));
        assets = new AssetFixtures(mockMvc, fx);
        org = fx.orgWithAdmin();
    }

    private UUID video(byte[] bytes, String profile) throws Exception {
        return assets.upload(org.admin(), org.id(), "VIDEO", "video/mp4", bytes, ",\"contentProfile\":\"%s\"".formatted(profile));
    }

    private int attempts(UUID assetId) {
        return jdbc.sql("SELECT transcode_attempts FROM content_asset WHERE id = :id").param("id", assetId).query(Integer.class).single();
    }

    @Test
    void videoIsTranscodedVerifiedAndReady() throws Exception {
        UUID id = video(MP4, "SLIDES");
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.durationMs").value(30000))
            .andExpect(jsonPath("$.height").value(1080))
            .andExpect(jsonPath("$.renditions", hasSize(2)))
            .andExpect(jsonPath("$.renditions[*].height", containsInAnyOrder(720, 480)))
            .andExpect(jsonPath("$.renditions[0].segmentCount").value(5));

        String masterKey = jdbc.sql("SELECT master_playlist_key FROM content_asset WHERE id = :id").param("id", id)
            .query(String.class).single();
        assertThat(masterKey).startsWith("delivery/org/" + org.id() + "/asset/" + id + "/g1/hls/");
        try (InputStream in = storage.open(masterKey)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains("RESOLUTION=1280x720").contains("720p/index.m3u8");
        }
    }

    @Test
    void screenDetailVideosGet1080p() throws Exception {
        UUID id = video(MP4, "SCREEN_DETAIL");
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.renditions[*].height", containsInAnyOrder(1080, 720, 480)));
    }

    @Test
    void filesThatArentVideoAreRejected() throws Exception {
        UUID id = video("just some text pretending".getBytes(), "SLIDES");
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.failureCode").value("unsupported_media"));
    }

    @Test
    void failedTranscodesAreRetriedThenTheAssetFailsAndCanBeReprocessed() throws Exception {
        UUID id = video("....ftyp FAIL_TRANSCODE".getBytes(), "SLIDES");
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("transcode_failed"));
        assertThat(attempts(id)).isEqualTo(3);

        fx.send(post("/api/assets/{id}/reprocess", id), org.admin(), null)
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("UPLOADED"));
        jobs.drain();
        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("FAILED"));
        int generation = jdbc.sql("SELECT processing_generation FROM content_asset WHERE id = :id").param("id", id)
            .query(Integer.class).single();
        assertThat(generation).isEqualTo(2);
    }

    @Test
    void incompleteOutputIsNeverMarkedReady() throws Exception {
        UUID id = video("....ftyp BAD_OUTPUT".getBytes(), "SLIDES");
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("output_invalid"));
    }

    @Test
    void duplicateOrStaleJobsChangeNothing() throws Exception {
        UUID id = video(MP4, "SLIDES");
        jobs.drain();

        scheduler.enqueue(JobRequest.of("MEDIA_PROCESS", "dup-" + UUID.randomUUID(), Map.of("assetId", id.toString())));
        scheduler.enqueue(JobRequest.of("MEDIA_CHECK", "stale-" + UUID.randomUUID(),
            Map.of("assetId", id.toString(), "generation", 1, "poll", 1)));
        jobs.drain();

        fx.send(get("/api/assets/{id}", id), org.admin(), null)
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.renditions", hasSize(2)));
        int generation = jdbc.sql("SELECT processing_generation FROM content_asset WHERE id = :id").param("id", id)
            .query(Integer.class).single();
        assertThat(generation).isEqualTo(1);
        fx.send(post("/api/assets/{id}/reprocess", id), org.admin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_reprocessable"));
    }
}
