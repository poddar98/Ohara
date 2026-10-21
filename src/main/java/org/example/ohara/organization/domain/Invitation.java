package org.example.ohara.organization.domain;

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

/** An emailed invitation to join an organization (and optionally a cohort). Only the token's hash is stored. */
@Entity
@Table(name = "invitation")
public class Invitation {

    public enum Status { PENDING, ACCEPTED, EXPIRED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "cohort_id")
    private UUID cohortId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgRole role;

    @Column
    private String firstName;

    @Column
    private String lastName;

    @Column
    private String externalRef;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private UUID invitedBy;

    @Column(nullable = false)
    private int sendCount = 1;

    @Column(nullable = false)
    private Instant lastSentAt;

    @Column
    private UUID acceptedByUserId;

    @Column
    private Instant acceptedAt;

    @Column
    private Instant revokedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Invitation() {
    }

    public Invitation(UUID organizationId, UUID cohortId, String email, OrgRole role, String firstName, String lastName,
                      String externalRef, String tokenHash, Instant expiresAt, UUID invitedBy) {
        Instant now = Instant.now();
        this.organizationId = organizationId;
        this.cohortId = cohortId;
        this.email = email;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
        this.externalRef = externalRef;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
        this.lastSentAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** PENDING invitations past their expiry are reported as EXPIRED even before a sweep updates the row. */
    public Status effectiveStatus(Instant now) {
        return status == Status.PENDING && !expiresAt.isAfter(now) ? Status.EXPIRED : status;
    }

    public void expireIfDue(Instant now) {
        if (effectiveStatus(now) == Status.EXPIRED && status == Status.PENDING) {
            status = Status.EXPIRED;
            updatedAt = now;
        }
    }

    /** Re-sending invalidates the previous link and restarts the expiry window. */
    public void reissue(String newTokenHash, Instant newExpiry, OrgRole newRole, UUID newCohortId, Instant now) {
        this.tokenHash = newTokenHash;
        this.expiresAt = newExpiry;
        this.role = newRole;
        this.cohortId = newCohortId;
        this.status = Status.PENDING;
        this.sendCount++;
        this.lastSentAt = now;
        this.updatedAt = now;
    }

    public void accept(UUID userId, Instant now) {
        this.status = Status.ACCEPTED;
        this.acceptedByUserId = userId;
        this.acceptedAt = now;
        this.updatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = Status.REVOKED;
        this.revokedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCohortId() { return cohortId; }
    public String getEmail() { return email; }
    public OrgRole getRole() { return role; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getExternalRef() { return externalRef; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getSendCount() { return sendCount; }
    public Instant getLastSentAt() { return lastSentAt; }
    public UUID getAcceptedByUserId() { return acceptedByUserId; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
