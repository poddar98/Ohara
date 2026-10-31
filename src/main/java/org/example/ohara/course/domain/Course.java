package org.example.ohara.course.domain;

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
import java.util.UUID;

/** The stable learning product. Title and slug survive versions; the curriculum lives in {@link CourseVersion}. */
@Entity
@Table(name = "course")
public class Course {

    public enum Visibility { PUBLIC, ORG_PRIVATE, UNLISTED }

    public enum Status { DRAFT, ACTIVE, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null for platform-owned (catalog) courses. */
    @Column(name = "owner_organization_id")
    private UUID ownerOrganizationId;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "current_published_version_id")
    private UUID currentPublishedVersionId;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected Course() {
    }

    public Course(UUID ownerOrganizationId, String slug, String title, Visibility visibility, UUID createdBy) {
        this.ownerOrganizationId = ownerOrganizationId;
        this.slug = slug;
        this.title = title;
        this.visibility = visibility;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
        touch();
    }

    public void changeVisibility(Visibility newVisibility) {
        this.visibility = newVisibility;
        touch();
    }

    /** First publish activates the course; later publishes just move the current version. */
    public void published(UUID versionId) {
        this.currentPublishedVersionId = versionId;
        if (status == Status.DRAFT) {
            status = Status.ACTIVE;
        }
        touch();
    }

    public void retire() {
        this.status = Status.RETIRED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerOrganizationId() { return ownerOrganizationId; }
    public boolean isPlatformOwned() { return ownerOrganizationId == null; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public Visibility getVisibility() { return visibility; }
    public Status getStatus() { return status; }
    public UUID getCurrentPublishedVersionId() { return currentPublishedVersionId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Optimistic-lock version; also the resource's ETag. */
    public int getVersion() { return version; }
}
