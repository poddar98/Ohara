package org.example.ohara.enrollment.api.dto;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The enrolled version's outline with lock flags; lesson bodies are fetched per lesson (and checked again there). */
public record LearnerCurriculum(UUID enrollmentId, UUID courseId, String courseTitle, UUID courseVersionId,
                                boolean hasAccess, Instant accessUntil, BigDecimal progressPercent, List<Module> modules) {

    public record Module(UUID id, String title, List<LessonItem> lessons) {
    }

    /** {@code progress}: NOT_STARTED, IN_PROGRESS or COMPLETED. */
    public record LessonItem(UUID id, UUID lessonKey, String title, String type, boolean preview,
                             Integer durationSeconds, boolean locked, String progress) {
    }
}
