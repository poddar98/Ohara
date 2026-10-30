package org.example.ohara.enrollment.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LessonProgressTest {

    private final UUID lessonKey = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-11-01T10:00:00Z");

    private LessonProgress fresh() {
        Enrollment enrollment = new Enrollment();
        ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(enrollment, "userId", UUID.randomUUID());
        LessonProgress p = new LessonProgress(enrollment, lessonKey, lessonId);
        ReflectionTestUtils.setField(p, "updatedAt", t0);
        return p;
    }

    @Test
    void watchedTimeIsCreditedNoFasterThanTheClock() {
        LessonProgress p = fresh();
        p.heartbeat(lessonId, 30_000L, null, 600_000, t0, 600_000L, true, t0, true);
        assertThat(p.getWatchedMs()).isEqualTo(30_000); // first heartbeat capped at 30 s

        p.heartbeat(lessonId, 60_000L, null, 600_000, t0.plusSeconds(20), 600_000L, true, t0.plusSeconds(20), false);
        assertThat(p.getWatchedMs()).isEqualTo(30_000 + 25_000); // 20 s elapsed + 5 s slack
    }

    @Test
    void videoCompletesAtNinetyPercentWatched() {
        LessonProgress p = fresh();
        assertThat(p.heartbeat(lessonId, 30_000L, null, 30_000, t0, 60_000L, true, t0, true)).isFalse();
        Instant later = t0.plusSeconds(30);
        boolean completed = p.heartbeat(lessonId, 55_000L, null, 24_000, later, 60_000L, true, later, false);

        assertThat(completed).isTrue();
        assertThat(p.getStatus()).isEqualTo(LessonProgress.Status.COMPLETED);
        assertThat(p.getCompletedAt()).isEqualTo(later);
    }

    @Test
    void staleHeartbeatsDontMoveThePositionBack() {
        LessonProgress p = fresh();
        p.heartbeat(lessonId, 40_000L, null, 0, t0.plusSeconds(10), 600_000L, true, t0, true);
        p.heartbeat(lessonId, 5_000L, null, 0, t0.plusSeconds(5), 600_000L, true, t0.plusSeconds(1), false);

        assertThat(p.getVideoPositionMs()).isEqualTo(40_000);
    }

    @Test
    void nonVideoLessonsOnlyCompleteExplicitly() {
        LessonProgress p = fresh();
        assertThat(p.heartbeat(lessonId, null, 1_000L, 30_000, t0, null, false, t0, true)).isFalse();
        assertThat(p.complete(t0)).isTrue();
        assertThat(p.complete(t0.plusSeconds(1))).isFalse();
    }
}
