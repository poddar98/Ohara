package org.example.ohara.common.web;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.CourseFixtures;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EtagIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    private static MockHttpServletRequestBuilder ifMatch(MockHttpServletRequestBuilder request, String etag) {
        return request.header("If-Match", etag);
    }

    @Test
    void aStaleEditIsRefusedInsteadOfOverwritingSomeoneElsesChange() throws Exception {
        Org org = fx.orgWithAdmin();
        String etag = fx.send(get("/api/orgs/{id}", org.id()), org.admin(), null).andExpect(status().isOk())
            .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");

        // Admin A saves first; the ETag moves on.
        String next = fx.send(ifMatch(patch("/api/orgs/{id}", org.id()), etag), org.admin(), "{\"name\":\"Renamed by A\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed by A"))
            .andReturn().getResponse().getHeader("ETag");
        assertThat(next).isNotEqualTo(etag);

        // Admin B still holds the old ETag: refused, told the current one.
        fx.send(ifMatch(patch("/api/orgs/{id}", org.id()), etag), org.admin(), "{\"name\":\"Renamed by B\"}")
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("precondition_failed"))
            .andExpect(jsonPath("$.currentETag").value(next));
        fx.send(get("/api/orgs/{id}", org.id()), org.admin(), null).andExpect(jsonPath("$.name").value("Renamed by A"));

        // Weak form and "*" match; no If-Match is still accepted (for now).
        fx.send(ifMatch(patch("/api/orgs/{id}", org.id()), "W/" + next), org.admin(), "{\"name\":\"Weak\"}").andExpect(status().isOk());
        fx.send(ifMatch(patch("/api/orgs/{id}", org.id()), "*"), org.admin(), "{\"name\":\"Any\"}").andExpect(status().isOk());
        fx.send(patch("/api/orgs/{id}", org.id()), org.admin(), "{\"name\":\"Unconditional\"}").andExpect(status().isOk());
    }

    @Test
    void cohortsCoursesAndOfferingsAreProtectedToo() throws Exception {
        Org org = fx.orgWithAdmin();
        UUID cohortId = fx.cohort(org, "E" + UUID.randomUUID().toString().substring(0, 4));
        String cohortTag = fx.send(get("/api/orgs/{o}/cohorts/{c}", org.id(), cohortId), org.admin(), null)
            .andReturn().getResponse().getHeader("ETag");
        fx.send(ifMatch(patch("/api/orgs/{o}/cohorts/{c}", org.id(), cohortId), cohortTag), org.admin(), "{\"name\":\"Batch A\"}")
            .andExpect(status().isOk());
        fx.send(ifMatch(patch("/api/orgs/{o}/cohorts/{c}", org.id(), cohortId), cohortTag), org.admin(), "{\"name\":\"Batch B\"}")
            .andExpect(status().isPreconditionFailed());

        UUID courseId = new CourseFixtures(fx).published(org.admin(), org.id()).courseId();
        String courseTag = fx.send(get("/api/courses/{id}", courseId), org.admin(), null)
            .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
        fx.send(ifMatch(patch("/api/courses/{id}", courseId), courseTag), org.admin(), "{\"title\":\"Algorithms II\"}")
            .andExpect(status().isOk());
        fx.send(ifMatch(patch("/api/courses/{id}", courseId), courseTag), org.admin(), "{\"title\":\"Algorithms III\"}")
            .andExpect(status().isPreconditionFailed());

        String offering = fx.send(post("/api/orgs/{o}/offerings", org.id()), org.admin(),
                "{\"courseId\":\"%s\",\"cohortId\":\"%s\"}".formatted(courseId, cohortId))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String offeringId = JsonPath.read(offering, "$.id");
        String offeringTag = "\"" + JsonPath.read(offering, "$.version") + "\"";
        fx.send(ifMatch(patch("/api/orgs/{o}/offerings/{id}", org.id(), offeringId), offeringTag), org.admin(),
            "{\"endsAt\":\"2030-01-01T00:00:00Z\"}").andExpect(status().isOk());
        fx.send(ifMatch(patch("/api/orgs/{o}/offerings/{id}", org.id(), offeringId), offeringTag), org.admin(),
            "{\"endsAt\":\"2031-01-01T00:00:00Z\"}").andExpect(status().isPreconditionFailed());
    }
}
