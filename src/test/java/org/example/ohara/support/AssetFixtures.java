package org.example.ohara.support;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.TestUsers.TestUser;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uploads through the real protocol: start, presign part 1, PUT to storage, complete. */
public class AssetFixtures {

    private final MockMvc mockMvc;
    private final OrgFixtures fx;

    public AssetFixtures(MockMvc mockMvc, OrgFixtures fx) {
        this.mockMvc = mockMvc;
        this.fx = fx;
    }

    /** Returns the asset id. {@code extraJson} is appended to the start request (e.g. ",\"contentProfile\":\"SLIDES\""). */
    public UUID upload(TestUser as, UUID orgId, String type, String mime, byte[] bytes, String extraJson) throws Exception {
        String owner = orgId == null ? "" : ",\"ownerOrganizationId\":\"%s\"".formatted(orgId);
        String started = fx.send(post("/api/assets"), as,
                "{\"type\":\"%s\",\"title\":\"Lecture\",\"fileName\":\"lecture.bin\",\"bytes\":%d,\"mimeType\":\"%s\"%s%s}"
                    .formatted(type, bytes.length, mime, owner, extraJson))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(started, "$.uploadId");
        String url = JsonPath.read(fx.send(post("/api/uploads/{id}/parts", uploadId), as, "{\"partNumbers\":[1]}")
            .andReturn().getResponse().getContentAsString(), "$[0].url");
        String etag = mockMvc.perform(put(URI.create(url)).content(bytes)).andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");
        fx.send(post("/api/uploads/{id}/complete", uploadId), as,
                "{\"parts\":[{\"partNumber\":1,\"etag\":\"%s\"}]}".formatted(etag.replace("\"", "\\\"")))
            .andExpect(status().isOk());
        return UUID.fromString(JsonPath.read(started, "$.asset.id"));
    }
}
