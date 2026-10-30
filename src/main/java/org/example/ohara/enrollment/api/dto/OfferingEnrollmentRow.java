package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.Enrollment;

import java.time.Instant;
import java.util.UUID;

public record OfferingEnrollmentRow(UUID enrollmentId, UUID userId, String email, String name, Enrollment.Status status,
                                    Instant enrolledAt, boolean hasAccessNow, Instant accessUntil) {
}
