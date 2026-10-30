package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;

import java.time.Instant;
import java.util.UUID;

public record MyCourseResponse(UUID enrollmentId, UUID courseId, String courseTitle, UUID offeringId,
                               CourseOffering.Channel channel, UUID organizationId, UUID courseVersionId,
                               Enrollment.Status status, boolean hasAccess, Instant accessUntil, Instant enrolledAt) {
}
