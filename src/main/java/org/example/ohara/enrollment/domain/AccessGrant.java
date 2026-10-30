package org.example.ohara.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Why, and from when until when, a learner may access an enrollment's content. Never deleted: revocation sets
 * {@code revokedAt}, so the table is also the access history and the contract team's reconciliation record.
 */
@Entity
@Table(name = "access_grant")
public class AccessGrant {

    public enum Source { COHORT_ASSIGNMENT, CONTRACT, ADMIN_GRANT, PROMOTION }

    public enum RevokedReason { REMOVED_FROM_COHORT, TRANSFERRED, REFUNDED_OFFLINE, CONTRACT_ENDED, ADMIN, REPLACED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column
    private UUID sourceRefId;

    @Column
    private String externalReference;

    @Column
    private String note;

    @Column(nullable = false)
    private Instant validFrom;

    @Column
    private Instant validUntil;

    @Column
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column
    private RevokedReason revokedReason;

    @Column
    private UUID revokedBy;

    @Column
    private UUID grantedBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected AccessGrant() {
    }

    public AccessGrant(Enrollment enrollment, Source source, UUID sourceRefId, String externalReference, String note,
                       Instant validFrom, Instant validUntil, UUID grantedBy) {
        this.enrollmentId = enrollment.getId();
        this.userId = enrollment.getUserId();
        this.source = source;
        this.sourceRefId = sourceRefId;
        this.externalReference = externalReference;
        this.note = note;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.grantedBy = grantedBy;
        this.createdAt = Instant.now();
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && !validFrom.isAfter(now) && (validUntil == null || validUntil.isAfter(now));
    }

    /** Not revoked and not yet expired (may start in the future). */
    public boolean isLive(Instant now) {
        return revokedAt == null && (validUntil == null || validUntil.isAfter(now));
    }

    public void revoke(RevokedReason reason, UUID by) {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
            this.revokedReason = reason;
            this.revokedBy = by;
        }
    }

    public void reschedule(Instant from, Instant until) {
        this.validFrom = from;
        this.validUntil = until;
    }

    public UUID getId() { return id; }
    public UUID getEnrollmentId() { return enrollmentId; }
    public UUID getUserId() { return userId; }
    public Source getSource() { return source; }
    public UUID getSourceRefId() { return sourceRefId; }
    public String getExternalReference() { return externalReference; }
    public String getNote() { return note; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getRevokedAt() { return revokedAt; }
    public RevokedReason getRevokedReason() { return revokedReason; }
    public UUID getRevokedBy() { return revokedBy; }
    public UUID getGrantedBy() { return grantedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
