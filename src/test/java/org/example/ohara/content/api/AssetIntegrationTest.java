package org.example.ohara.content.api;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.content.application.AssetService;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.example.ohara.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssetIntegrationTest {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] VIDEO = "....ftypisom fake mp4 bytes".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AssetService assetService;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static String json(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private ResultActions start(TestUser as, UUID orgId, String type, String mime, long bytes, String extra) throws Exception {
        String owner = orgId == null ? "" : ",\"ownerOrganizationId\":\"%s\"".formatted(orgId);
        return fx.send(post("/api/assets"), as, "{\"type\":\"%s\",\"title\":\"Lecture notes\",\"fileName\":\"notes.pdf\",\"bytes\":%d,\"mimeType\":\"%s\"%s%s}"
            .formatted(type, bytes, mime, owner, extra));
    }

    /** Presigns part 1, PUTs the bytes to the storage URL, returns the ETag. */
    private String putPart(TestUser as, String uploadId, byte[] bytes) throws Exception {
        String url = JsonPath.read(json(fx.send(post("/api/uploads/{id}/parts", uploadId), as, "{\"partNumbers\":[1]}")
            .andExpect(status().isOk())), "$[0].url");
        return mockMvc.perform(put(URI.create(url)).content(bytes))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private ResultActions complete(TestUser as, String uploadId, String etag) throws Exception {
        return fx.send(post("/api/uploads/{id}/complete", uploadId), as,
            "{\"parts\":[{\"partNumber\":1,\"etag\":\"%s\"}]}".formatted(etag.replace("\"", "\\\"")));
    }

    /** Full upload; returns the asset id. */
    private String upload(TestUser as, UUID orgId, String type, String mime, byte[] bytes) throws Exception {
        String started = json(start(as, orgId, type, mime, bytes.length, "").andExpect(status().isCreated()));
        String uploadId = JsonPath.read(started, "$.uploadId");
        complete(as, uploadId, putPart(as, uploadId, bytes)).andExpect(status().isOk());
        return JsonPath.read(started, "$.asset.id");
    }

    // --- uploads -------------------------------------------------------------------------------------

    @Test
    void documentUploadsStraightToStorageAndIsReady() throws Exception {
        Org org = fx.orgWithAdmin();
        String started = json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", PDF.length,
                ",\"sha256\":\"%s\"".formatted(sha256(PDF)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.partCount").value(1))
            .andExpect(jsonPath("$.asset.status").value("PENDING_UPLOAD")));
        String uploadId = JsonPath.read(started, "$.uploadId");
        String etag = putPart(org.admin(), uploadId, PDF);

        fx.send(get("/api/uploads/{id}", uploadId), org.admin(), null)
            .andExpect(jsonPath("$.uploadedParts", hasSize(1)))
            .andExpect(jsonPath("$.uploadedParts[0].size").value(PDF.length));
        complete(org.admin(), uploadId, etag)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.bytes").value(PDF.length));
        // Completing again is harmless.
        complete(org.admin(), uploadId, etag).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));

        fx.send(get("/api/assets").param("ownerOrganizationId", org.id().toString()), org.admin(), null)
            .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void contentChecksRejectBadFiles() throws Exception {
        Org org = fx.orgWithAdmin();

        // Renamed file: not actually a PDF.
        String fake = upload(org.admin(), org.id(), "DOCUMENT", "application/pdf", "not a pdf at all".getBytes());
        fx.send(get("/api/assets/{id}", fake), org.admin(), null)
            .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.failureCode").value("invalid_file"));

        // Declared checksum doesn't match the bytes.
        String started = json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", PDF.length,
            ",\"sha256\":\"%s\"".formatted("0".repeat(64))));
        String uploadId = JsonPath.read(started, "$.uploadId");
        complete(org.admin(), uploadId, putPart(org.admin(), uploadId, PDF))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("checksum_mismatch"))
            .andExpect(jsonPath("$.asset.status").value("REJECTED"));

        // Fewer bytes than declared.
        started = json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", PDF.length + 10, ""));
        uploadId = JsonPath.read(started, "$.uploadId");
        complete(org.admin(), uploadId, putPart(org.admin(), uploadId, PDF))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("size_mismatch"));
    }

    @Test
    void uploadRequestsAreValidated() throws Exception {
        Org org = fx.orgWithAdmin();

        start(org.admin(), org.id(), "DOCUMENT", "application/x-msdownload", 10, "")
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unsupported_type"));
        start(org.admin(), org.id(), "DOCUMENT", "application/pdf", 200L * 1024 * 1024, "")
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("file_too_large"));
    }

    @Test
    void retriedStartReturnsTheSameUpload() throws Exception {
        Org org = fx.orgWithAdmin();
        String key = UUID.randomUUID().toString();
        String body = "{\"type\":\"DOCUMENT\",\"title\":\"T\",\"fileName\":\"a.pdf\",\"bytes\":10,\"mimeType\":\"application/pdf\",\"ownerOrganizationId\":\"%s\"}"
            .formatted(org.id());

        String first = json(mockMvc.perform(post("/api/assets").header("Authorization", org.admin().bearer())
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()));
        String second = json(mockMvc.perform(post("/api/assets").header("Authorization", org.admin().bearer())
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()));

        assertThat((String) JsonPath.read(second, "$.uploadId")).isEqualTo(JsonPath.read(first, "$.uploadId"));
    }

    @Test
    void presignedUrlsCantBeTamperedWithOrOverfilled() throws Exception {
        Org org = fx.orgWithAdmin();
        String uploadId = JsonPath.read(json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", 10, "")), "$.uploadId");
        String url = JsonPath.read(json(fx.send(post("/api/uploads/{id}/parts", uploadId), org.admin(), "{\"partNumbers\":[1]}")), "$[0].url");

        mockMvc.perform(put(URI.create(url + "x")).content("hello".getBytes())).andExpect(status().isForbidden());
        mockMvc.perform(put(URI.create(url)).content("way more than ten bytes".getBytes())).andExpect(status().isBadRequest());
        // Someone else can't drive another person's upload.
        fx.send(post("/api/uploads/{id}/parts", uploadId), users.user(), "{\"partNumbers\":[1]}").andExpect(status().isNotFound());
    }

    @Test
    void videoUploadIsQueuedForProcessing() throws Exception {
        Org org = fx.orgWithAdmin();
        String started = json(start(org.admin(), org.id(), "VIDEO", "video/mp4", VIDEO.length, ",\"contentProfile\":\"SCREEN_DETAIL\""));
        String uploadId = JsonPath.read(started, "$.uploadId");

        complete(org.admin(), uploadId, putPart(org.admin(), uploadId, VIDEO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UPLOADED"))
            .andExpect(jsonPath("$.contentProfile").value("SCREEN_DETAIL"));
        String assetId = JsonPath.read(started, "$.asset.id");
        int jobs = jdbc.sql("SELECT count(*) FROM processing_job WHERE job_type = 'MEDIA_PROCESS' AND payload->>'assetId' = :id")
            .param("id", assetId).query(Integer.class).single();
        assertThat(jobs).isEqualTo(1);
    }

    @Test
    void abortedAndAbandonedUploadsCancelTheAsset() throws Exception {
        Org org = fx.orgWithAdmin();
        String aborted = json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", 10, ""));
        fx.send(post("/api/uploads/{id}/abort", (String) JsonPath.read(aborted, "$.uploadId")), org.admin(), null)
            .andExpect(status().isNoContent());
        fx.send(get("/api/assets/{id}", (String) JsonPath.read(aborted, "$.asset.id")), org.admin(), null)
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        String abandoned = json(start(org.admin(), org.id(), "DOCUMENT", "application/pdf", 10, ""));
        jdbc.sql("UPDATE media_upload SET expires_at = now() - interval '1 minute' WHERE id = :id")
            .param("id", UUID.fromString(JsonPath.read(abandoned, "$.uploadId"))).update();
        assertThat(assetService.expireAbandonedUploads()).isGreaterThanOrEqualTo(1);
        fx.send(get("/api/assets/{id}", (String) JsonPath.read(abandoned, "$.asset.id")), org.admin(), null)
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void assetsBelongToTheirOwner() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        TestUser learner = fx.member(org, "LEARNER");
        String assetId = upload(org.admin(), org.id(), "DOCUMENT", "application/pdf", PDF);

        start(learner, org.id(), "DOCUMENT", "application/pdf", 10, "").andExpect(status().isForbidden());
        start(users.user(), null, "DOCUMENT", "application/pdf", 10, "").andExpect(status().isForbidden());
        fx.send(get("/api/assets/{id}", assetId), other.admin(), null).andExpect(status().isNotFound());
        fx.send(patch("/api/assets/{id}", assetId), other.admin(), "{\"title\":\"x\"}").andExpect(status().isNotFound());
    }

    // --- lessons -------------------------------------------------------------------------------------

    @Test
    void documentsInLessonsReachLearnersThroughShortLivedLinks() throws Exception {
        Org org = fx.orgWithAdmin();
        String assetId = upload(org.admin(), org.id(), "DOCUMENT", "application/pdf", PDF);
        String videoId = JsonPath.read(json(start(org.admin(), org.id(), "VIDEO", "video/mp4", VIDEO.length, "")), "$.asset.id");

        String course = json(fx.send(post("/api/courses"), org.admin(), "{\"title\":\"Notes\",\"slug\":\"n-%s\",\"ownerOrganizationId\":\"%s\"}"
            .formatted(UUID.randomUUID().toString().substring(0, 10), org.id())));
        String base = "/api/courses/" + JsonPath.read(course, "$.id") + "/versions/" + JsonPath.read(course, "$.draftVersionId");
        fx.send(patch(base), org.admin(), "{\"description\":\"d\"}");
        String moduleId = JsonPath.read(json(fx.send(post(base + "/modules"), org.admin(), "{\"title\":\"M\"}")), "$.modules[0].id");
        String lessonId = JsonPath.read(json(fx.send(post(base + "/modules/" + moduleId + "/lessons"), org.admin(),
            "{\"title\":\"Read me\",\"type\":\"READING\",\"preview\":true}")), "$.modules[0].lessons[0].id");

        // Attaching checks owner and type.
        String foreign = upload(fx.platformAdmin(), null, "DOCUMENT", "application/pdf", PDF);
        fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(), "{\"kind\":\"DOCUMENT\",\"contentAssetId\":\"%s\"}".formatted(foreign))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("asset_not_found"));
        fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(), "{\"kind\":\"VIDEO\",\"contentAssetId\":\"%s\"}".formatted(assetId))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("asset_type_mismatch"));
        fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(), "{\"kind\":\"DOCUMENT\",\"contentAssetId\":\"%s\"}".formatted(assetId))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.modules[0].lessons[0].contents[0].contentAssetId").value(assetId));

        // A video that isn't processed yet blocks publishing.
        fx.send(post(base + "/lessons/" + lessonId + "/contents"), org.admin(), "{\"kind\":\"VIDEO\",\"contentAssetId\":\"%s\"}".formatted(videoId))
            .andExpect(status().isCreated());
        fx.send(post(base + "/publish"), org.admin(), "{}")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errors[0]").value(containsString("isn't ready")));
        String tree = json(fx.send(get(base), org.admin(), null));
        String videoContentId = JsonPath.read(tree, "$.modules[0].lessons[0].contents[1].id");
        fx.send(delete(base + "/contents/" + videoContentId), org.admin(), null).andExpect(status().isOk());
        fx.send(post(base + "/publish"), org.admin(), "{}").andExpect(status().isOk());

        // Learner (preview lesson) gets a working, short-lived link.
        String lesson = json(fx.send(get("/api/lessons/{id}", lessonId), users.user(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.contents[0].available").value(true)));
        String download = JsonPath.read(lesson, "$.contents[0].downloadUrl");
        mockMvc.perform(get(URI.create(download)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("notes.pdf")))
            .andExpect(content().bytes(PDF));

        // In use: can't be deleted, only retired — which makes it unavailable to learners.
        fx.send(delete("/api/assets/{id}", assetId), org.admin(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("asset_in_use"));
        fx.send(post("/api/assets/{id}/retire", assetId), org.admin(), null).andExpect(jsonPath("$.status").value("RETIRED"));
        fx.send(get("/api/lessons/{id}", lessonId), users.user(), null)
            .andExpect(jsonPath("$.contents[0].available").value(false))
            .andExpect(jsonPath("$.contents[0].downloadUrl").doesNotExist());
        fx.send(delete("/api/assets/{id}", foreign), fx.platformAdmin(), null).andExpect(status().isNoContent());
    }
}
