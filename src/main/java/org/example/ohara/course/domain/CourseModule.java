package org.example.ohara.course.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "course_module")
public class CourseModule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_version_id", nullable = false)
    private CourseVersion version;

    @Column(nullable = false)
    private UUID moduleKey;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int position;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 100)
    private List<Lesson> lessons = new ArrayList<>();

    protected CourseModule() {
    }

    CourseModule(CourseVersion version, UUID moduleKey, String title, int position) {
        this.version = version;
        this.moduleKey = moduleKey;
        this.title = title;
        this.position = position;
    }

    CourseModule copyInto(CourseVersion target) {
        CourseModule copy = new CourseModule(target, moduleKey, title, position);
        for (Lesson lesson : lessons) {
            copy.lessons.add(lesson.copyInto(copy));
        }
        return copy;
    }

    public Lesson addLesson(String lessonTitle, Lesson.Type type, boolean preview, Integer durationSeconds) {
        Lesson lesson = new Lesson(this, UUID.randomUUID(), lessonTitle, type, lessons.size(), preview, durationSeconds);
        lessons.add(lesson);
        version.touch();
        return lesson;
    }

    public void removeLesson(Lesson lesson) {
        lessons.remove(lesson);
        CourseVersion.renumber(lessons, Lesson::setPosition);
        version.touch();
    }

    public void reorderLessons(List<UUID> orderedIds) {
        CourseVersion.reorder(lessons, orderedIds, Lesson::getId, Lesson::setPosition);
        version.touch();
    }

    public void rename(String newTitle) {
        this.title = newTitle;
        version.touch();
    }

    void setPosition(int position) {
        this.position = position;
    }

    public UUID getId() { return id; }
    public CourseVersion getVersion() { return version; }
    public UUID getModuleKey() { return moduleKey; }
    public String getTitle() { return title; }
    public int getPosition() { return position; }
    public List<Lesson> getLessons() { return lessons; }
}
