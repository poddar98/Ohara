package org.example.ohara.course.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

/**
 * A snapshot of a course's curriculum. Only a DRAFT may change; publishing freezes it (also enforced by DB triggers).
 * A new draft is a deep copy of a published version that keeps module and lesson keys, so progress carries over.
 */
@Entity
@Table(name = "course_version")
public class CourseVersion {

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    public enum Level { BEGINNER, INTERMEDIATE, ADVANCED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private String title;

    @Column
    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String language = "en";

    @Enumerated(EnumType.STRING)
    @Column
    private Level level;

    @Column
    private Integer estimatedMinutes;

    @Column
    private UUID basedOnVersionId;

    @Column(columnDefinition = "text")
    private String changeNotes;

    @Column
    private Instant publishedAt;

    @Column
    private UUID publishedBy;

    @Column
    private Instant archivedAt;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 50)
    private List<CourseModule> modules = new ArrayList<>();

    protected CourseVersion() {
    }

    public CourseVersion(UUID courseId, int versionNumber, String title, UUID createdBy) {
        this.courseId = courseId;
        this.versionNumber = versionNumber;
        this.title = title;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Deep copy into a new draft; ids are new, module and lesson keys are kept. */
    public CourseVersion draftCopy(int newNumber, UUID by) {
        CourseVersion copy = new CourseVersion(courseId, newNumber, title, by);
        copy.subtitle = subtitle;
        copy.description = description;
        copy.language = language;
        copy.level = level;
        copy.estimatedMinutes = estimatedMinutes;
        copy.basedOnVersionId = id;
        for (CourseModule module : modules) {
            copy.modules.add(module.copyInto(copy));
        }
        return copy;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public void describe(String title, String subtitle, String description, String language, Level level, Integer minutes) {
        if (title != null) this.title = title;
        if (subtitle != null) this.subtitle = subtitle;
        if (description != null) this.description = description;
        if (language != null) this.language = language;
        if (level != null) this.level = level;
        if (minutes != null) this.estimatedMinutes = minutes;
        touch();
    }

    public CourseModule addModule(String moduleTitle) {
        CourseModule module = new CourseModule(this, UUID.randomUUID(), moduleTitle, modules.size());
        modules.add(module);
        touch();
        return module;
    }

    public void removeModule(CourseModule module) {
        modules.remove(module);
        renumber(modules, CourseModule::setPosition);
        touch();
    }

    /** {@code orderedIds} must be exactly the current module ids. */
    public void reorderModules(List<UUID> orderedIds) {
        reorder(modules, orderedIds, CourseModule::getId, CourseModule::setPosition);
        touch();
    }

    public void publish(UUID by, String notes) {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.publishedBy = by;
        this.changeNotes = notes;
        touch();
    }

    public void archive() {
        this.status = Status.ARCHIVED;
        this.archivedAt = Instant.now();
        touch();
    }

    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Positions are kept contiguous (0..n-1) after removals. */
    static <T> void renumber(List<T> items, ObjIntConsumer<T> setPosition) {
        for (int i = 0; i < items.size(); i++) {
            setPosition.accept(items.get(i), i);
        }
    }

    static <T> void reorder(List<T> items, List<UUID> orderedIds, Function<T, UUID> idOf, ObjIntConsumer<T> setPosition) {
        if (orderedIds.size() != items.size()
            || !items.stream().map(idOf).collect(Collectors.toSet()).equals(new HashSet<>(orderedIds))) {
            throw new IllegalArgumentException("The new order must list every item exactly once");
        }
        for (T item : items) {
            setPosition.accept(item, orderedIds.indexOf(idOf.apply(item)));
        }
        items.sort(Comparator.comparingInt(item -> orderedIds.indexOf(idOf.apply(item))));
    }

    public UUID getId() { return id; }
    public UUID getCourseId() { return courseId; }
    public int getVersionNumber() { return versionNumber; }
    public Status getStatus() { return status; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getDescription() { return description; }
    public String getLanguage() { return language; }
    public Level getLevel() { return level; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public UUID getBasedOnVersionId() { return basedOnVersionId; }
    public String getChangeNotes() { return changeNotes; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<CourseModule> getModules() { return modules; }
}
