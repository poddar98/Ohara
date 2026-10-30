package org.example.ohara.course.domain;

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

import java.util.UUID;

/** One item in a lesson: inline reading text or an external link. Asset-backed kinds arrive with content assets. */
@Entity
@Table(name = "lesson_content")
public class LessonContent {

    public enum Kind { TEXT, EXTERNAL_LINK }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_version_id", nullable = false)
    private CourseVersion version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column
    private String externalUrl;

    @Column
    private UUID contentAssetId;

    @Column(nullable = false)
    private int position;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    protected LessonContent() {
    }

    private LessonContent(Lesson lesson, Kind kind, String title, String body, String externalUrl, int position, boolean required) {
        this.lesson = lesson;
        this.version = lesson.getVersion();
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.externalUrl = externalUrl;
        this.position = position;
        this.required = required;
    }

    static LessonContent text(Lesson lesson, String title, String body, int position, boolean required) {
        return new LessonContent(lesson, Kind.TEXT, title, body, null, position, required);
    }

    static LessonContent link(Lesson lesson, String title, String url, int position, boolean required) {
        return new LessonContent(lesson, Kind.EXTERNAL_LINK, title, null, url, position, required);
    }

    LessonContent copyInto(Lesson target) {
        LessonContent copy = new LessonContent(target, kind, title, body, externalUrl, position, required);
        copy.contentAssetId = contentAssetId;
        return copy;
    }

    /** Null arguments are unchanged; body/url only apply to the matching kind. */
    public void update(String newTitle, String newBody, String newUrl, Boolean newRequired) {
        if (newTitle != null) this.title = newTitle;
        if (newBody != null && kind == Kind.TEXT) this.body = newBody;
        if (newUrl != null && kind == Kind.EXTERNAL_LINK) this.externalUrl = newUrl;
        if (newRequired != null) this.required = newRequired;
        lesson.getModule().getVersion().touch();
    }

    void setPosition(int position) {
        this.position = position;
    }

    public UUID getId() { return id; }
    public Lesson getLesson() { return lesson; }
    public Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getExternalUrl() { return externalUrl; }
    public int getPosition() { return position; }
    public boolean isRequired() { return required; }
}
