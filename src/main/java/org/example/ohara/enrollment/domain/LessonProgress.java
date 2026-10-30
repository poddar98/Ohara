package org.example.ohara.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A learner's progress on one lesson of one enrollment, keyed by the lesson's stable key so it survives version
 * changes. Watched time is credited at most as fast as wall-clock time passes, so a client can't fake completion.
 */
@Entity
@Table(name = "lesson_progress")
public class LessonProgress {

    public enum Status { IN_PROGRESS, COMPLETED }

    /** Share of a video that must be watched to complete it. */
    public static final double COMPLETION_THRESHOLD = 0.9;
    /** Slack allowed between heartbeats for network jitter. */
    static final Duration HEARTBEAT_SLACK = Duration.ofSeconds(5);
    /** Most that a first heartbeat may claim. */
    static final Duration FIRST_HEARTBEAT_MAX = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(nullable = false)
    private UUID lessonKey;

    /** The version-specific lesson last worked on. */
    @Column(nullable = false)
    private UUID lessonId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    @Column
    private Long videoPositionMs;

    @Column
    private Long videoDurationMs;

    @Column(nullable = false)
    private long watchedMs;

    @Column
    private Instant completedAt;

    @Column
    private Instant clientUpdatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected LessonProgress() {
    }

    public LessonProgress(Enrollment enrollment, UUID lessonKey, UUID lessonId) {
        this.userId = enrollment.getUserId();
        this.enrollmentId = enrollment.getId();
        this.lessonKey = lessonKey;
        this.lessonId = lessonId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /**
     * Applies a player heartbeat. Out-of-order heartbeats (older client time) don't move the position back.
     * {@code knownDurationMs} (from the curriculum) wins over the client's report when present.
     *
     * @return true if this heartbeat completed the lesson
     */
    public boolean heartbeat(UUID currentLessonId, Long positionMs, Long reportedDurationMs, long watchedDeltaMs,
                             Instant clientTime, Long knownDurationMs, boolean videoLesson, Instant now, boolean isNew) {
        this.lessonId = currentLessonId;
        Duration allowance = isNew ? FIRST_HEARTBEAT_MAX : Duration.between(updatedAt, now).plus(HEARTBEAT_SLACK);
        long credited = Math.max(0, Math.min(watchedDeltaMs, allowance.toMillis()));

        boolean fresh = clientTime == null || clientUpdatedAt == null || clientTime.isAfter(clientUpdatedAt);
        Long duration = knownDurationMs != null ? knownDurationMs : (reportedDurationMs != null ? reportedDurationMs : videoDurationMs);
        if (duration != null && duration > 0) {
            this.videoDurationMs = duration;
        }
        if (fresh) {
            if (positionMs != null) {
                this.videoPositionMs = videoDurationMs != null ? Math.min(positionMs, videoDurationMs) : positionMs;
            }
            this.clientUpdatedAt = clientTime;
        }
        this.watchedMs = videoDurationMs != null ? Math.min(videoDurationMs, watchedMs + credited) : watchedMs + credited;
        this.updatedAt = now;

        if (videoLesson && status != Status.COMPLETED && videoDurationMs != null
            && watchedMs >= COMPLETION_THRESHOLD * videoDurationMs) {
            complete(now);
            return true;
        }
        return false;
    }

    /** Explicit completion (reading lessons, or a learner marking a lesson done). Idempotent. */
    public boolean complete(Instant now) {
        if (status == Status.COMPLETED) {
            return false;
        }
        this.status = Status.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
        return true;
    }

    public UUID getId() { return id; }
    public UUID getEnrollmentId() { return enrollmentId; }
    public UUID getLessonKey() { return lessonKey; }
    public UUID getLessonId() { return lessonId; }
    public Status getStatus() { return status; }
    public Long getVideoPositionMs() { return videoPositionMs; }
    public Long getVideoDurationMs() { return videoDurationMs; }
    public long getWatchedMs() { return watchedMs; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
