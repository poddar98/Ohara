package org.example.ohara.course.api.dto;

import org.example.ohara.course.domain.CourseModule;
import org.example.ohara.course.domain.CourseVersion;
import org.example.ohara.course.domain.Lesson;
import org.example.ohara.course.domain.LessonContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A version with its whole curriculum, for authoring screens. */
public record VersionTree(UUID id, UUID courseId, int versionNumber, CourseVersion.Status status, String title,
                          String subtitle, String description, String language, CourseVersion.Level level,
                          Integer estimatedMinutes, UUID basedOnVersionId, String changeNotes, Instant publishedAt,
                          List<ModuleNode> modules) {

    public record ModuleNode(UUID id, UUID moduleKey, String title, int position, List<LessonNode> lessons) {
    }

    public record LessonNode(UUID id, UUID lessonKey, String title, Lesson.Type type, int position, boolean preview,
                             Integer durationSeconds, List<ContentNode> contents) {
    }

    public record ContentNode(UUID id, LessonContent.Kind kind, String title, String body, String externalUrl,
                              UUID contentAssetId, int position, boolean required) {
    }

    public static VersionTree of(CourseVersion v) {
        return new VersionTree(v.getId(), v.getCourseId(), v.getVersionNumber(), v.getStatus(), v.getTitle(),
            v.getSubtitle(), v.getDescription(), v.getLanguage(), v.getLevel(), v.getEstimatedMinutes(),
            v.getBasedOnVersionId(), v.getChangeNotes(), v.getPublishedAt(),
            v.getModules().stream().map(VersionTree::module).toList());
    }

    private static ModuleNode module(CourseModule m) {
        return new ModuleNode(m.getId(), m.getModuleKey(), m.getTitle(), m.getPosition(),
            m.getLessons().stream().map(VersionTree::lesson).toList());
    }

    private static LessonNode lesson(Lesson l) {
        return new LessonNode(l.getId(), l.getLessonKey(), l.getTitle(), l.getType(), l.getPosition(), l.isPreview(),
            l.getDurationSeconds(), l.getContents().stream().map(VersionTree::content).toList());
    }

    private static ContentNode content(LessonContent c) {
        return new ContentNode(c.getId(), c.getKind(), c.getTitle(), c.getBody(), c.getExternalUrl(), c.getContentAssetId(),
            c.getPosition(), c.isRequired());
    }
}
