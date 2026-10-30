package org.example.ohara.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A reusable file (video, PDF, image). Lessons reference it; the bytes live in object storage.
 * <pre>
 * PENDING_UPLOAD → UPLOADED → PROCESSING → READY → RETIRED
 *        └→ CANCELLED   └→ REJECTED      └→ FAILED (retryable; the source is kept)
 * </pre>
 * Documents and images go straight from UPLOADED to READY; videos are transcoded first.
 */
@Entity
@Table(name = "content_asset")
public class ContentAsset {

    public enum Type { VIDEO, DOCUMENT, IMAGE }

    public enum Status { PENDING_UPLOAD, UPLOADED, PROCESSING, READY, FAILED, REJECTED, CANCELLED, RETIRED }

    /** Drives the video ladder: slides and talking heads don't need 1080p. */
    public enum ContentProfile { TALKING_HEAD, SLIDES, SCREEN_DETAIL }

    /** States in which an asset may be attached to a lesson. */
    public static final Set<Status> ATTACHABLE = EnumSet.of(Status.PENDING_UPLOAD, Status.UPLOADED, Status.PROCESSING,
        Status.READY, Status.FAILED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_organization_id")
    private UUID ownerOrganizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false)
    private Type type;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_status", nullable = false)
    private Status status = Status.PENDING_UPLOAD;

    @Column
    private String originalFilename;

    @Column
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column
    private ContentProfile contentProfile;

    @Column
    private String sourceObjectKey;

    @Column
    private String sourceVersionId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    @Column
    private Long sourceBytes;

    @Column
    private Long durationMs;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(nullable = false)
    private int processingGeneration;

    @Column
    private String deliveryPrefix;

    @Column
    private String masterPlaylistKey;

    @Column
    private String failureCode;

    @Column
    private String failureDetail;

    @Column
    private Instant readyAt;

    @Column
    private Instant retiredAt;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected ContentAsset() {
    }

    public ContentAsset(UUID ownerOrganizationId, Type type, String title, String originalFilename, String mimeType,
                        ContentProfile contentProfile, UUID createdBy) {
        this.ownerOrganizationId = ownerOrganizationId;
        this.type = type;
        this.title = title;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.contentProfile = type == Type.VIDEO ? (contentProfile != null ? contentProfile : ContentProfile.SLIDES) : null;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void uploaded(String objectKey, String versionId, long bytes, String sha256) {
        this.sourceObjectKey = objectKey;
        this.sourceVersionId = versionId;
        this.sourceBytes = bytes;
        this.sourceSha256 = sha256;
        this.status = Status.UPLOADED;
        touch();
    }

    /** Files that need no processing are usable as soon as they're stored. */
    public void readyAsIs() {
        this.status = Status.READY;
        this.readyAt = Instant.now();
        touch();
    }

    public void cancelled() {
        this.status = Status.CANCELLED;
        touch();
    }

    public void rejected(String code, String detail) {
        this.status = Status.REJECTED;
        this.failureCode = code;
        this.failureDetail = detail;
        touch();
    }

    public void retire() {
        this.status = Status.RETIRED;
        this.retiredAt = Instant.now();
        touch();
    }

    public void rename(String newTitle) {
        this.title = newTitle;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerOrganizationId() { return ownerOrganizationId; }
    public Type getType() { return type; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMimeType() { return mimeType; }
    public ContentProfile getContentProfile() { return contentProfile; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public String getSourceVersionId() { return sourceVersionId; }
    public Long getSourceBytes() { return sourceBytes; }
    public Long getDurationMs() { return durationMs; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public int getProcessingGeneration() { return processingGeneration; }
    public String getMasterPlaylistKey() { return masterPlaylistKey; }
    public String getFailureCode() { return failureCode; }
    public String getFailureDetail() { return failureDetail; }
    public Instant getReadyAt() { return readyAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
