package org.example.ohara.course.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.api.dto.ContentRequest;
import org.example.ohara.course.api.dto.LessonRequest;
import org.example.ohara.course.api.dto.UpdateVersionRequest;
import org.example.ohara.course.api.dto.VersionSummary;
import org.example.ohara.course.api.dto.VersionTree;
import org.example.ohara.course.application.CourseAccess.StaffRole;
import org.example.ohara.course.domain.Course;
import org.example.ohara.course.domain.CourseModule;
import org.example.ohara.course.domain.CourseVersion;
import org.example.ohara.course.domain.Lesson;
import org.example.ohara.course.domain.LessonContent;
import org.example.ohara.course.infrastructure.persistence.CourseRepository;
import org.example.ohara.course.infrastructure.persistence.CourseVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Versions and curriculum. Only a DRAFT is editable; publishing freezes it. Every id in a path is resolved inside the
 * course and version it is addressed through, so one course's ids can't reach another's curriculum.
 */
@Service
public class CurriculumService {

    private final CourseRepository courses;
    private final CourseVersionRepository versions;
    private final CourseAccess access;

    public CurriculumService(CourseRepository courses, CourseVersionRepository versions, CourseAccess access) {
        this.courses = courses;
        this.versions = versions;
        this.access = access;
    }

    // --- versions ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<VersionSummary> versions(CurrentActor actor, UUID courseId) {
        access.require(actor, course(courseId), StaffRole.READ_ONLY);
        return versions.findByCourseIdOrderByVersionNumberDesc(courseId).stream().map(VersionSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public VersionTree tree(CurrentActor actor, UUID courseId, UUID versionId) {
        access.require(actor, course(courseId), StaffRole.READ_ONLY);
        return VersionTree.of(version(courseId, versionId));
    }

    /** New draft copied from the current published version, keeping module and lesson keys. One draft at a time. */
    @Transactional
    public VersionTree createDraft(CurrentActor actor, UUID courseId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.INSTRUCTOR);
        if (versions.findByCourseIdAndStatus(courseId, CourseVersion.Status.DRAFT).isPresent()) {
            throw new ApiException.Conflict("draft_exists", "This course already has a draft");
        }
        if (course.getCurrentPublishedVersionId() == null) {
            throw new ApiException.Conflict("nothing_published", "Publish the first version before starting another");
        }
        CourseVersion source = versions.findById(course.getCurrentPublishedVersionId()).orElseThrow();
        CourseVersion draft = versions.save(source.draftCopy(versions.maxVersionNumber(courseId) + 1, actor.userId()));
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree describe(CurrentActor actor, UUID courseId, UUID versionId, UpdateVersionRequest r) {
        CourseVersion draft = draft(actor, courseId, versionId);
        draft.describe(trim(r.title()), r.subtitle(), r.description(), r.language(), r.level(), r.estimatedMinutes());
        return VersionTree.of(draft);
    }

    /** Discards a draft. The first version can't be discarded before anything was published. */
    @Transactional
    public void deleteDraft(CurrentActor actor, UUID courseId, UUID versionId) {
        CourseVersion draft = draft(actor, courseId, versionId);
        if (course(courseId).getCurrentPublishedVersionId() == null) {
            throw new ApiException.Conflict("only_version", "The first version can't be discarded; retire the course instead");
        }
        versions.delete(draft);
    }

    /**
     * Freezes the draft and makes it the course's current version. Learners already enrolled stay on the version they
     * enrolled in; new enrollments get this one.
     */
    @Transactional
    public VersionTree publish(CurrentActor actor, UUID courseId, UUID versionId, String changeNotes) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        CourseVersion draft = version(courseId, versionId);
        requireDraft(draft);
        if (course.getStatus() == Course.Status.RETIRED) {
            throw new ApiException.Conflict("course_retired", "Retired courses can't be published");
        }
        List<String> problems = publishProblems(draft);
        if (!problems.isEmpty()) {
            throw new ApiException.Unprocessable("not_publishable", "The draft isn't ready to publish").with("errors", problems);
        }
        draft.publish(actor.userId(), changeNotes);
        course.published(draft.getId());
        return VersionTree.of(draft);
    }

    /** Archives an old published version. The current version can't be archived (publish a newer one first). */
    @Transactional
    public VersionSummary archive(CurrentActor actor, UUID courseId, UUID versionId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        CourseVersion version = version(courseId, versionId);
        if (version.getStatus() != CourseVersion.Status.PUBLISHED) {
            throw new ApiException.Conflict("not_archivable", "Only published versions can be archived");
        }
        if (version.getId().equals(course.getCurrentPublishedVersionId())) {
            throw new ApiException.Conflict("current_version", "The current version can't be archived");
        }
        version.archive();
        return VersionSummary.of(version);
    }

    // --- modules -----------------------------------------------------------------------------------

    @Transactional
    public VersionTree addModule(CurrentActor actor, UUID courseId, UUID versionId, String title) {
        CourseVersion draft = draft(actor, courseId, versionId);
        draft.addModule(title.trim());
        return saved(draft);
    }

    @Transactional
    public VersionTree renameModule(CurrentActor actor, UUID courseId, UUID versionId, UUID moduleId, String title) {
        CourseVersion draft = draft(actor, courseId, versionId);
        module(draft, moduleId).rename(title.trim());
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree removeModule(CurrentActor actor, UUID courseId, UUID versionId, UUID moduleId) {
        CourseVersion draft = draft(actor, courseId, versionId);
        draft.removeModule(module(draft, moduleId));
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree reorderModules(CurrentActor actor, UUID courseId, UUID versionId, List<UUID> ids) {
        CourseVersion draft = draft(actor, courseId, versionId);
        reorder(() -> draft.reorderModules(ids));
        return VersionTree.of(draft);
    }

    // --- lessons -----------------------------------------------------------------------------------

    @Transactional
    public VersionTree addLesson(CurrentActor actor, UUID courseId, UUID versionId, UUID moduleId, LessonRequest r) {
        if (r.title() == null || r.type() == null) {
            throw new ApiException.BadRequest("lesson_incomplete", "title and type are required");
        }
        CourseVersion draft = draft(actor, courseId, versionId);
        module(draft, moduleId).addLesson(r.title().trim(), r.type(), Boolean.TRUE.equals(r.preview()), r.durationSeconds());
        return saved(draft);
    }

    @Transactional
    public VersionTree updateLesson(CurrentActor actor, UUID courseId, UUID versionId, UUID lessonId, LessonRequest r) {
        CourseVersion draft = draft(actor, courseId, versionId);
        lesson(draft, lessonId).update(trim(r.title()), r.type(), r.preview(), r.durationSeconds());
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree removeLesson(CurrentActor actor, UUID courseId, UUID versionId, UUID lessonId) {
        CourseVersion draft = draft(actor, courseId, versionId);
        Lesson lesson = lesson(draft, lessonId);
        lesson.getModule().removeLesson(lesson);
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree reorderLessons(CurrentActor actor, UUID courseId, UUID versionId, UUID moduleId, List<UUID> ids) {
        CourseVersion draft = draft(actor, courseId, versionId);
        CourseModule module = module(draft, moduleId);
        reorder(() -> module.reorderLessons(ids));
        return VersionTree.of(draft);
    }

    // --- contents ----------------------------------------------------------------------------------

    @Transactional
    public VersionTree addContent(CurrentActor actor, UUID courseId, UUID versionId, UUID lessonId, ContentRequest r) {
        CourseVersion draft = draft(actor, courseId, versionId);
        Lesson lesson = lesson(draft, lessonId);
        boolean required = r.required() == null || r.required();
        if (r.kind() == null) {
            throw new ApiException.BadRequest("content_incomplete", "kind is required");
        }
        switch (r.kind()) {
            case TEXT -> {
                if (r.body() == null || r.body().isBlank()) {
                    throw new ApiException.BadRequest("content_incomplete", "TEXT content needs a body");
                }
                lesson.addText(trim(r.title()), r.body(), required);
            }
            case EXTERNAL_LINK -> {
                if (r.externalUrl() == null) {
                    throw new ApiException.BadRequest("content_incomplete", "EXTERNAL_LINK content needs an externalUrl");
                }
                lesson.addLink(trim(r.title()), r.externalUrl(), required);
            }
        }
        return saved(draft);
    }

    @Transactional
    public VersionTree updateContent(CurrentActor actor, UUID courseId, UUID versionId, UUID contentId, ContentRequest r) {
        CourseVersion draft = draft(actor, courseId, versionId);
        LessonContent content = content(draft, contentId);
        if (r.kind() != null && r.kind() != content.getKind()) {
            throw new ApiException.BadRequest("kind_immutable", "Content kind can't change; remove and add instead");
        }
        content.update(trim(r.title()), r.body(), r.externalUrl(), r.required());
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree removeContent(CurrentActor actor, UUID courseId, UUID versionId, UUID contentId) {
        CourseVersion draft = draft(actor, courseId, versionId);
        LessonContent content = content(draft, contentId);
        content.getLesson().removeContent(content);
        return VersionTree.of(draft);
    }

    @Transactional
    public VersionTree reorderContents(CurrentActor actor, UUID courseId, UUID versionId, UUID lessonId, List<UUID> ids) {
        CourseVersion draft = draft(actor, courseId, versionId);
        Lesson lesson = lesson(draft, lessonId);
        reorder(() -> lesson.reorderContents(ids));
        return VersionTree.of(draft);
    }

    // --- helpers -----------------------------------------------------------------------------------

    static List<String> publishProblems(CourseVersion draft) {
        List<String> problems = new ArrayList<>();
        if (draft.getDescription() == null || draft.getDescription().isBlank()) {
            problems.add("The course needs a description");
        }
        if (draft.getModules().isEmpty()) {
            problems.add("The course needs at least one module");
        }
        for (CourseModule module : draft.getModules()) {
            if (module.getLessons().isEmpty()) {
                problems.add("Module '" + module.getTitle() + "' has no lessons");
            }
            for (Lesson lesson : module.getLessons()) {
                if (lesson.getContents().isEmpty()) {
                    problems.add("Lesson '" + lesson.getTitle() + "' has no content");
                }
            }
        }
        return problems;
    }

    /** Flush so newly added items get ids before they're returned. */
    private VersionTree saved(CourseVersion draft) {
        versions.saveAndFlush(draft);
        return VersionTree.of(draft);
    }

    private static void reorder(Runnable change) {
        try {
            change.run();
        } catch (IllegalArgumentException ex) {
            throw new ApiException.BadRequest("invalid_order", ex.getMessage());
        }
    }

    private CourseVersion draft(CurrentActor actor, UUID courseId, UUID versionId) {
        access.require(actor, course(courseId), StaffRole.INSTRUCTOR);
        CourseVersion version = version(courseId, versionId);
        requireDraft(version);
        return version;
    }

    private static void requireDraft(CourseVersion version) {
        if (!version.isDraft()) {
            throw new ApiException.Conflict("version_frozen",
                "Version " + version.getVersionNumber() + " is " + version.getStatus().name().toLowerCase()
                    + "; start a new draft to make changes");
        }
    }

    private Course course(UUID courseId) {
        return courses.findById(courseId).orElseThrow(CourseAccess::notFound);
    }

    private CourseVersion version(UUID courseId, UUID versionId) {
        return versions.findByIdAndCourseId(versionId, courseId)
            .orElseThrow(() -> new ApiException.NotFound("version_not_found", "Version not found"));
    }

    private static CourseModule module(CourseVersion version, UUID moduleId) {
        return version.getModules().stream().filter(m -> m.getId().equals(moduleId)).findFirst()
            .orElseThrow(() -> new ApiException.NotFound("module_not_found", "Module not found in this version"));
    }

    private static Lesson lesson(CourseVersion version, UUID lessonId) {
        return version.getModules().stream().flatMap(m -> m.getLessons().stream())
            .filter(l -> l.getId().equals(lessonId)).findFirst()
            .orElseThrow(() -> new ApiException.NotFound("lesson_not_found", "Lesson not found in this version"));
    }

    private static LessonContent content(CourseVersion version, UUID contentId) {
        return version.getModules().stream().flatMap(m -> m.getLessons().stream()).flatMap(l -> l.getContents().stream())
            .filter(c -> c.getId().equals(contentId)).findFirst()
            .orElseThrow(() -> new ApiException.NotFound("content_not_found", "Content not found in this version"));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
