package org.example.ohara.live.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A learner's aggregated attendance at one session: joins and player heartbeats, never raw events. Time is credited
 * at most at wall-clock rate and only while the class is on air. The status is decided when the session ends;
 * a manual override (source MANUAL) is never recomputed.
 */
@Entity
@Table(name = "live_attendance")
@IdClass(LiveAttendance.Key.class)
public class LiveAttendance {

    public enum Status { PRESENT, PARTIAL, ABSENT }

    public enum Source { PLAYER_HEARTBEAT, MANUAL }

    public record Key(UUID sessionId, UUID userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "live_session_id")
    private UUID sessionId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column
    private Instant firstJoinedAt;

    @Column
    private Instant lastSeenAt;

    @Column(nullable = false)
    private int joinCount;

    @Column(nullable = false)
    private int attendedSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status")
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.PLAYER_HEARTBEAT;

    @Column
    private String note;

    protected LiveAttendance() {
    }

    public LiveAttendance(UUID sessionId, UUID userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public void joined(Instant now) {
        if (firstJoinedAt == null) {
            firstJoinedAt = now;
        }
        joinCount++;
        lastSeenAt = now;
    }

    /** Credits the time since the previous sign of life, but never more than {@code maxGap} at once. */
    public void heartbeat(Instant now, boolean onAir, Duration maxGap) {
        if (onAir && lastSeenAt != null && now.isAfter(lastSeenAt)) {
            long gap = Math.min(Duration.between(lastSeenAt, now).toSeconds(), maxGap.toSeconds());
            attendedSeconds += (int) gap;
        }
        lastSeenAt = now;
    }

    /** Status from attended time; manual overrides stand. */
    public void decide(long airedSeconds, double presentRatio) {
        if (source == Source.MANUAL) {
            return;
        }
        if (attendedSeconds == 0) {
            status = Status.ABSENT;
        } else if (airedSeconds > 0 && attendedSeconds >= presentRatio * airedSeconds) {
            status = Status.PRESENT;
        } else {
            status = Status.PARTIAL;
        }
    }

    public void override(Status status, String note) {
        this.status = status;
        this.note = note;
        this.source = Source.MANUAL;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getUserId() { return userId; }
    public Instant getFirstJoinedAt() { return firstJoinedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public int getJoinCount() { return joinCount; }
    public int getAttendedSeconds() { return attendedSeconds; }
    public Status getStatus() { return status; }
    public Source getSource() { return source; }
    public String getNote() { return note; }
}
