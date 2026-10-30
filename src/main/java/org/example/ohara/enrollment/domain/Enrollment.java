package org.example.ohara.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A learner's relationship with an offering, pinned to the course version they study. Access itself comes from
 * {@link AccessGrant}s; the enrollment is never deleted, so progress and history survive revocation.
 */
@Entity
@Table(name = "enrollment")
public class Enrollment {

    public enum Status { ACTIVE, SUSPENDED, COMPLETED, EXPIRED, REVOKED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "course_offering_id", nullable = false)
    private UUID courseOfferingId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "course_version_id", nullable = false)
    private UUID courseVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant enrolledAt;

    @Column
    private Instant completedAt;

    @Column
    private Instant endedAt;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal progressPercent = BigDecimal.ZERO;

    @Column
    private Instant lastActivityAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected Enrollment() {
    }

    public Enrollment(UUID userId, CourseOffering offering, UUID courseVersionId) {
        Instant now = Instant.now();
        this.userId = userId;
        this.courseOfferingId = offering.getId();
        this.courseId = offering.getCourseId();
        this.organizationId = offering.getOrganizationId();
        this.courseVersionId = courseVersionId;
        this.enrolledAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** No grant left: REVOKED (access taken away) or CANCELLED (refund). Completed enrollments keep their status. */
    public void accessEnded(Status reason) {
        if (status == Status.ACTIVE || status == Status.SUSPENDED) {
            this.status = reason;
            this.endedAt = Instant.now();
            touch();
        }
    }

    /** A new grant re-activates an enrollment whose access had ended. */
    public void reactivate() {
        if (status == Status.REVOKED || status == Status.CANCELLED || status == Status.EXPIRED) {
            this.status = Status.ACTIVE;
            this.endedAt = null;
            touch();
        }
    }

    public boolean isStudying() {
        return status == Status.ACTIVE || status == Status.COMPLETED;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCourseOfferingId() { return courseOfferingId; }
    public UUID getCourseId() { return courseId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCourseVersionId() { return courseVersionId; }
    public Status getStatus() { return status; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getEndedAt() { return endedAt; }
    public BigDecimal getProgressPercent() { return progressPercent; }
}
