package org.example.ohara.enrollment.api.dto;

import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.domain.LessonProgress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgressSummary(UUID enrollmentId, UUID courseVersionId, Enrollment.Status status, BigDecimal progressPercent,
                              int completedLessons, int totalLessons, Instant completedAt, List<Item> lessons) {

    public record Item(UUID lessonKey, UUID lessonId, LessonProgress.Status status, Long positionMs, Long durationMs,
                       long watchedMs, Instant completedAt, Instant updatedAt) {
    }
}
