package org.example.ohara.support;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.TestUsers.TestUser;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Organizations, cohorts and members set up through the real API. */
public class OrgFixtures {

    public record Org(UUID id, TestUser admin) {
    }

    private final MockMvc mockMvc;
    private final TestUsers users;
    private final TestUser platformAdmin;

    public OrgFixtures(MockMvc mockMvc, TestUsers users) throws Exception {
        this.mockMvc = mockMvc;
        this.users = users;
        this.platformAdmin = users.platformAdmin();
    }

    public TestUser platformAdmin() {
        return platformAdmin;
    }

    public ResultActions send(MockHttpServletRequestBuilder request, TestUser as, String json) throws Exception {
        if (as != null) {
            request.header("Authorization", as.bearer());
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    public Org orgWithAdmin() throws Exception {
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 12);
        String body = send(post("/api/admin/organizations"), platformAdmin,
                "{\"name\":\"College %s\",\"slug\":\"%s\",\"type\":\"COLLEGE\"}".formatted(slug, slug))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID orgId = UUID.fromString(JsonPath.read(body, "$.id"));
        TestUser admin = users.user();
        addMember(orgId, platformAdmin, admin, "ORG_ADMIN").andExpect(status().isCreated());
        return new Org(orgId, admin);
    }

    public UUID cohort(Org org, String code) throws Exception {
        String body = send(post("/api/orgs/{orgId}/cohorts", org.id()), org.admin(),
                "{\"name\":\"Batch %s\",\"code\":\"%s\"}".formatted(code, code))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public TestUser member(Org org, String... roles) throws Exception {
        TestUser user = users.user();
        addMember(org.id(), org.admin(), user, roles).andExpect(status().isCreated());
        return user;
    }

    public ResultActions addMember(UUID orgId, TestUser as, TestUser member, String... roles) throws Exception {
        String rolesJson = Arrays.stream(roles).map(r -> "\"" + r + "\"").collect(Collectors.joining(","));
        return send(post("/api/orgs/{orgId}/members", orgId), as,
            "{\"email\":\"%s\",\"roles\":[%s]}".formatted(member.email(), rolesJson));
    }
}
