package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.CourseOffering;

import java.time.Instant;
import java.util.UUID;

public record OfferingResponse(UUID id, UUID courseId, String courseTitle, CourseOffering.Channel channel,
                               UUID organizationId, UUID cohortId, String title, CourseOffering.VersionPolicy versionPolicy,
                               UUID pinnedVersionId, Instant startsAt, Instant endsAt, Integer accessDurationDays,
                               Long listPriceMinor, String currency, CourseOffering.Status status, Instant createdAt) {

    public static OfferingResponse of(CourseOffering o, String courseTitle) {
        return new OfferingResponse(o.getId(), o.getCourseId(), courseTitle, o.getChannel(), o.getOrganizationId(),
            o.getCohortId(), o.getTitle(), o.getVersionPolicy(), o.getPinnedCourseVersionId(), o.getStartsAt(),
            o.getEndsAt(), o.getAccessDurationDays(), o.getListPriceMinor(), o.getCurrency(), o.getStatus(), o.getCreatedAt());
    }
}
