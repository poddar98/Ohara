package org.example.ohara.course.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code lessonKey} identifies "the same lesson" across versions; learner progress is keyed by it. */
@Entity
@Table(name = "lesson")
public class Lesson {

    public enum Type { VIDEO, READING, QUIZ, ASSIGNMENT, LIVE, MIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_module_id", nullable = false)
    private CourseModule module;

    // Denormalized for one-query curriculum loads; an entity reference so new (unsaved) drafts get the FK on persist.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_version_id", nullable = false)
    private CourseVersion version;

    @Column(nullable = false)
    private UUID lessonKey;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private int position;

    @Column(name = "is_preview", nullable = false)
    private boolean preview;

    @Column
    private Integer durationSeconds;

    @OneToMany(mappedBy = "lesson", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 100)
    private List<LessonContent> contents = new ArrayList<>();

    protected Lesson() {
    }

    Lesson(CourseModule module, UUID lessonKey, String title, Type type, int position, boolean preview, Integer durationSeconds) {
        this.module = module;
        this.version = module.getVersion();
        this.lessonKey = lessonKey;
        this.title = title;
        this.type = type;
        this.position = position;
        this.preview = preview;
        this.durationSeconds = durationSeconds;
    }

    Lesson copyInto(CourseModule target) {
        Lesson copy = new Lesson(target, lessonKey, title, type, position, preview, durationSeconds);
        for (LessonContent content : contents) {
            copy.contents.add(content.copyInto(copy));
        }
        return copy;
    }

    public LessonContent addText(String title, String body, boolean required) {
        return add(LessonContent.text(this, title, body, contents.size(), required));
    }

    public LessonContent addLink(String title, String url, boolean required) {
        return add(LessonContent.link(this, title, url, contents.size(), required));
    }

    private LessonContent add(LessonContent content) {
        contents.add(content);
        module.getVersion().touch();
        return content;
    }

    public void removeContent(LessonContent content) {
        contents.remove(content);
        CourseVersion.renumber(contents, LessonContent::setPosition);
        module.getVersion().touch();
    }

    public void reorderContents(List<UUID> orderedIds) {
        CourseVersion.reorder(contents, orderedIds, LessonContent::getId, LessonContent::setPosition);
        module.getVersion().touch();
    }

    public void update(String newTitle, Type newType, Boolean newPreview, Integer newDuration) {
        if (newTitle != null) this.title = newTitle;
        if (newType != null) this.type = newType;
        if (newPreview != null) this.preview = newPreview;
        if (newDuration != null) this.durationSeconds = newDuration;
        module.getVersion().touch();
    }

    void setPosition(int position) {
        this.position = position;
    }

    public UUID getId() { return id; }
    public CourseModule getModule() { return module; }
    public CourseVersion getVersion() { return version; }
    public UUID getLessonKey() { return lessonKey; }
    public String getTitle() { return title; }
    public Type getType() { return type; }
    public int getPosition() { return position; }
    public boolean isPreview() { return preview; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public List<LessonContent> getContents() { return contents; }
}
