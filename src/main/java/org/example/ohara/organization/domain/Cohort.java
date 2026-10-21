package org.example.ohara.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "cohort")
public class Cohort {

    public enum Status { PLANNED, ACTIVE, COMPLETED, ARCHIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column
    private LocalDate startsOn;

    @Column
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLANNED;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private UUID createdBy;

    @Version
    private int version;

    protected Cohort() {
    }

    public Cohort(UUID organizationId, String name, String code, LocalDate startsOn, LocalDate endsOn, UUID createdBy) {
        this.organizationId = organizationId;
        this.name = name;
        this.code = code;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        reschedule(startsOn, endsOn);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public Status getStatus() {
        return status;
    }

    /** Members can be placed only while the cohort is still running or being set up. */
    public boolean acceptsMembers() {
        return status == Status.PLANNED || status == Status.ACTIVE;
    }

    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = Instant.now();
    }

    public void reschedule(LocalDate newStart, LocalDate newEnd) {
        if (newStart != null && newEnd != null && newEnd.isBefore(newStart)) {
            throw new IllegalArgumentException("endsOn must not be before startsOn");
        }
        this.startsOn = newStart;
        this.endsOn = newEnd;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(Status newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
}
