package org.example.ohara.organization.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A person's affiliation with one organization. Grants no course access by itself. */
@Entity
@Table(name = "organization_membership")
public class OrganizationMembership {

    public enum Status { ACTIVE, SUSPENDED, REMOVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "organization_membership_role", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @BatchSize(size = 100)
    private Set<OrgRole> roles = new HashSet<>();

    @Column
    private String externalRef;

    @Column(nullable = false)
    private Instant joinedAt;

    @Column
    private Instant removedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrganizationMembership() {
    }

    public OrganizationMembership(UUID organizationId, User user, Set<OrgRole> roles, String externalRef) {
        Instant now = Instant.now();
        this.organizationId = organizationId;
        this.user = user;
        this.roles = EnumSet.copyOf(roles);
        this.externalRef = externalRef;
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

    public User getUser() {
        return user;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Set<OrgRole> getRoles() {
        return Set.copyOf(roles);
    }

    public boolean hasRole(OrgRole role) {
        return roles.contains(role);
    }

    public String getExternalRef() {
        return externalRef;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void addRoles(Set<OrgRole> added) {
        roles.addAll(added);
        touch();
    }

    public void replaceRoles(Set<OrgRole> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
        touch();
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
        touch();
    }

    public void suspend() {
        status = Status.SUSPENDED;
        touch();
    }

    /** Back to ACTIVE, from SUSPENDED or REMOVED (re-joining keeps the original row and its history). */
    public void activate() {
        if (status == Status.REMOVED) {
            joinedAt = Instant.now();
            removedAt = null;
        }
        status = Status.ACTIVE;
        touch();
    }

    public void remove() {
        status = Status.REMOVED;
        removedAt = Instant.now();
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
