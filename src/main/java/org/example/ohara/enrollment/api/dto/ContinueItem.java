package org.example.ohara.enrollment.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ContinueItem(UUID enrollmentId, UUID courseId, String courseTitle, UUID lessonId, UUID lessonKey,
                           String lessonTitle, Long positionMs, Long durationMs, Instant updatedAt) {
}
