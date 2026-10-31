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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * How a course is delivered. B2B: to an organization (usually a cohort) within a window. B2C: publicly listed; the
 * contract team grants access after an offline payment, for {@code accessDurationDays}.
 */
@Entity
@Table(name = "course_offering")
public class CourseOffering {

    public enum Channel { B2B, B2C }

    public enum VersionPolicy { LATEST_ON_ENROLL, PINNED }

    public enum Status { DRAFT, OPEN, CLOSED, ARCHIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "cohort_id")
    private UUID cohortId;

    @Column
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionPolicy versionPolicy = VersionPolicy.LATEST_ON_ENROLL;

    @Column(name = "pinned_course_version_id")
    private UUID pinnedCourseVersionId;

    @Column
    private Instant startsAt;

    @Column
    private Instant endsAt;

    @Column
    private Integer accessDurationDays;

    @Column
    private Long listPriceMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected CourseOffering() {
    }

    public static CourseOffering b2b(UUID courseId, UUID organizationId, UUID cohortId, String title,
                                     Instant startsAt, Instant endsAt, UUID createdBy) {
        CourseOffering o = new CourseOffering(courseId, Channel.B2B, createdBy);
        o.organizationId = organizationId;
        o.cohortId = cohortId;
        o.title = title;
        o.reschedule(startsAt, endsAt);
        return o;
    }

    public static CourseOffering b2c(UUID courseId, String title, Integer accessDurationDays, Long listPriceMinor,
                                     String currency, UUID createdBy) {
        CourseOffering o = new CourseOffering(courseId, Channel.B2C, createdBy);
        o.title = title;
        o.accessDurationDays = accessDurationDays;
        o.listPriceMinor = listPriceMinor;
        o.currency = currency;
        return o;
    }

    private CourseOffering(UUID courseId, Channel channel, UUID createdBy) {
        this.courseId = courseId;
        this.channel = channel;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void pinTo(UUID versionId) {
        this.versionPolicy = versionId == null ? VersionPolicy.LATEST_ON_ENROLL : VersionPolicy.PINNED;
        this.pinnedCourseVersionId = versionId;
        touch();
    }

    public void reschedule(Instant newStart, Instant newEnd) {
        if (newStart != null && newEnd != null && !newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        this.startsAt = newStart;
        this.endsAt = newEnd;
        touch();
    }

    public void open() {
        this.status = Status.OPEN;
        touch();
    }

    public void close() {
        this.status = Status.CLOSED;
        touch();
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** Start of access for a new grant: the offering start (B2B) or now. */
    public Instant accessStart(Instant now) {
        return startsAt != null && startsAt.isAfter(now) ? startsAt : now;
    }

    /** End of access for a new grant: the offering end (B2B) or now + duration (B2C); null = open-ended. */
    public Instant accessEnd(Instant now) {
        if (channel == Channel.B2C && accessDurationDays != null) {
            return now.plus(accessDurationDays, ChronoUnit.DAYS);
        }
        return endsAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public Channel getChannel() { return channel; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCohortId() { return cohortId; }
    public String getTitle() { return title; }
    public VersionPolicy getVersionPolicy() { return versionPolicy; }
    public UUID getPinnedCourseVersionId() { return pinnedCourseVersionId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public Integer getAccessDurationDays() { return accessDurationDays; }
    public Long getListPriceMinor() { return listPriceMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    /** Optimistic-lock version; also the resource's ETag. */
    public int getVersion() { return version; }
}
