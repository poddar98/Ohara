package org.example.ohara.course.api.dto;

import org.example.ohara.course.domain.Course;

import java.time.Instant;
import java.util.UUID;

public record CourseResponse(UUID id, UUID ownerOrganizationId, String slug, String title, Course.Visibility visibility,
                             Course.Status status, UUID currentPublishedVersionId, UUID draftVersionId,
                             Instant createdAt, Instant updatedAt) {

    public static CourseResponse of(Course c, UUID draftVersionId) {
        return new CourseResponse(c.getId(), c.getOwnerOrganizationId(), c.getSlug(), c.getTitle(), c.getVisibility(),
            c.getStatus(), c.getCurrentPublishedVersionId(), draftVersionId, c.getCreatedAt(), c.getUpdatedAt());
    }
}
