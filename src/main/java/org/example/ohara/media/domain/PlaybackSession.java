package org.example.ohara.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One authorization to stream one video; refreshed (with a fresh access check) before it expires. */
@Entity
@Table(name = "playback_session")
public class PlaybackSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID lessonId;

    @Column(nullable = false)
    private UUID contentId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column
    private UUID enrollmentId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int refreshCount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant refreshedAt;

    protected PlaybackSession() {
    }

    public PlaybackSession(UUID userId, UUID lessonId, UUID contentId, UUID assetId, UUID enrollmentId, Instant expiresAt) {
        this.userId = userId;
        this.lessonId = lessonId;
        this.contentId = contentId;
        this.assetId = assetId;
        this.enrollmentId = enrollmentId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void refreshed(Instant newExpiry) {
        this.expiresAt = newExpiry;
        this.refreshCount++;
        this.refreshedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getLessonId() { return lessonId; }
    public UUID getContentId() { return contentId; }
    public UUID getAssetId() { return assetId; }
    public Instant getExpiresAt() { return expiresAt; }
}
