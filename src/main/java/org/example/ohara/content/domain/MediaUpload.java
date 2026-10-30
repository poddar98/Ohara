package org.example.ohara.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One resumable multipart upload of an asset's source file, straight from the client to object storage. */
@Entity
@Table(name = "media_upload")
public class MediaUpload {

    public enum Status { INITIATED, COMPLETED, ABORTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_asset_id", nullable = false)
    private UUID contentAssetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.INITIATED;

    @Column(nullable = false)
    private String storageUploadId;

    @Column(nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private long declaredBytes;

    @Column(nullable = false)
    private String declaredMime;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "declared_sha256", length = 64)
    private String declaredSha256;

    @Column(nullable = false)
    private long partSizeBytes;

    @Column(nullable = false)
    private int partCount;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant completedAt;

    @Column(nullable = false)
    private UUID initiatedBy;

    @Column(nullable = false)
    private String clientKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected MediaUpload() {
    }

    public MediaUpload(UUID contentAssetId, String storageUploadId, String objectKey, long declaredBytes, String declaredMime,
                       String declaredSha256, long partSizeBytes, int partCount, Instant expiresAt, UUID initiatedBy,
                       String clientKey) {
        this.contentAssetId = contentAssetId;
        this.storageUploadId = storageUploadId;
        this.objectKey = objectKey;
        this.declaredBytes = declaredBytes;
        this.declaredMime = declaredMime;
        this.declaredSha256 = declaredSha256;
        this.partSizeBytes = partSizeBytes;
        this.partCount = partCount;
        this.expiresAt = expiresAt;
        this.initiatedBy = initiatedBy;
        this.clientKey = clientKey;
        this.createdAt = Instant.now();
    }

    public boolean isOpen(Instant now) {
        return status == Status.INITIATED && expiresAt.isAfter(now);
    }

    public void completed() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void aborted() {
        this.status = Status.ABORTED;
    }

    public void expired() {
        this.status = Status.EXPIRED;
    }

    public UUID getId() { return id; }
    public UUID getContentAssetId() { return contentAssetId; }
    public Status getStatus() { return status; }
    public String getStorageUploadId() { return storageUploadId; }
    public String getObjectKey() { return objectKey; }
    public long getDeclaredBytes() { return declaredBytes; }
    public String getDeclaredMime() { return declaredMime; }
    public String getDeclaredSha256() { return declaredSha256; }
    public long getPartSizeBytes() { return partSizeBytes; }
    public int getPartCount() { return partCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getClientKey() { return clientKey; }
}
