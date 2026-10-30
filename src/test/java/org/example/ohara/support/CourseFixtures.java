package org.example.ohara.support;

import com.jayway.jsonpath.JsonPath;
import org.example.ohara.support.TestUsers.TestUser;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Published courses built through the real authoring API. */
public class CourseFixtures {

    /** A published course: one module, a free-preview lesson and a regular (locked) lesson. */
    public record PublishedCourse(UUID courseId, UUID versionId, UUID previewLessonId, UUID lockedLessonId,
                                  UUID previewLessonKey, UUID lockedLessonKey) {
    }

    private final OrgFixtures fx;

    public CourseFixtures(OrgFixtures fx) {
        this.fx = fx;
    }

    public PublishedCourse published(TestUser author, UUID ownerOrganizationId) throws Exception {
        String org = ownerOrganizationId == null ? "" : ",\"ownerOrganizationId\":\"%s\"".formatted(ownerOrganizationId);
        String course = fx.send(post("/api/courses"), author,
                "{\"title\":\"Algorithms\",\"slug\":\"c-%s\"%s}".formatted(UUID.randomUUID().toString().substring(0, 12), org))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String courseId = JsonPath.read(course, "$.id");
        String versionId = JsonPath.read(course, "$.draftVersionId");
        String base = "/api/courses/" + courseId + "/versions/" + versionId;

        fx.send(patch(base), author, "{\"description\":\"Sorting and searching.\"}").andExpect(status().isOk());
        String tree = fx.send(post(base + "/modules"), author, "{\"title\":\"Basics\"}")
            .andReturn().getResponse().getContentAsString();
        String moduleId = JsonPath.read(tree, "$.modules[0].id");
        fx.send(post(base + "/modules/" + moduleId + "/lessons"), author, "{\"title\":\"Welcome\",\"type\":\"READING\",\"preview\":true}")
            .andExpect(status().isCreated());
        tree = fx.send(post(base + "/modules/" + moduleId + "/lessons"), author, "{\"title\":\"Quicksort\",\"type\":\"READING\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String previewId = JsonPath.read(tree, "$.modules[0].lessons[0].id");
        String lockedId = JsonPath.read(tree, "$.modules[0].lessons[1].id");
        fx.send(post(base + "/lessons/" + previewId + "/contents"), author, "{\"kind\":\"TEXT\",\"body\":\"Hello!\"}")
            .andExpect(status().isCreated());
        fx.send(post(base + "/lessons/" + lockedId + "/contents"), author, "{\"kind\":\"TEXT\",\"body\":\"Pick a pivot...\"}")
            .andExpect(status().isCreated());
        tree = fx.send(post(base + "/publish"), author, "{}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        return new PublishedCourse(UUID.fromString(courseId), UUID.fromString(versionId), UUID.fromString(previewId),
            UUID.fromString(lockedId), UUID.fromString(JsonPath.read(tree, "$.modules[0].lessons[0].lessonKey")),
            UUID.fromString(JsonPath.read(tree, "$.modules[0].lessons[1].lessonKey")));
    }
}
