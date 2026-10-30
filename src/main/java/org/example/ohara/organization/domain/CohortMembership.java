package org.example.ohara.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.ohara.user.domain.User;

import java.time.Instant;
import java.util.UUID;

/** A learner's placement in a cohort. Ending it keeps the row (left_at) so progress and attendance history survive. */
@Entity
@Table(name = "cohort_membership")
public class CohortMembership {

    public enum Status { ACTIVE, TRANSFERRED, REMOVED, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant joinedAt;

    @Column
    private Instant leftAt;

    @Column
    private UUID transferredToCohortId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CohortMembership() {
    }

    public CohortMembership(Cohort cohort, User user) {
        Instant now = Instant.now();
        this.cohortId = cohort.getId();
        this.organizationId = cohort.getOrganizationId();
        this.user = user;
        this.joinedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getCohortId() {
        return cohortId;
    }

    public User getUser() {
        return user;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public void end(Status reason) {
        end(reason, null);
    }

    public void transferTo(UUID targetCohortId) {
        end(Status.TRANSFERRED, targetCohortId);
    }

    private void end(Status reason, UUID targetCohortId) {
        if (reason == Status.ACTIVE) {
            throw new IllegalArgumentException("An ending status is required");
        }
        Instant now = Instant.now();
        this.status = reason;
        this.leftAt = now;
        this.transferredToCohortId = targetCohortId;
        this.updatedAt = now;
    }
}
