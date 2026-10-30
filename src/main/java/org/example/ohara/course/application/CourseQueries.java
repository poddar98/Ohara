package org.example.ohara.course.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.course.api.dto.VersionTree;
import org.example.ohara.course.domain.Course;
import org.example.ohara.course.domain.CourseVersion;
import org.example.ohara.course.infrastructure.persistence.CourseRepository;
import org.example.ohara.course.infrastructure.persistence.CourseVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;

/** Read-only course facts for other modules. No authorization here: callers decide who may see what. */
@Service
public class CourseQueries {

    public record CourseFacts(UUID id, UUID ownerOrganizationId, String slug, String title, Course.Visibility visibility,
                              Course.Status status, UUID currentPublishedVersionId) {
    }

    public record VersionFacts(UUID id, UUID courseId, int versionNumber, CourseVersion.Status status) {
    }

    public record LessonFacts(UUID lessonId, UUID lessonKey, UUID versionId, UUID courseId, boolean preview, String title) {
    }

    private final CourseRepository courses;
    private final CourseVersionRepository versions;
    private final CourseAccess access;
    private final EntityManager entityManager;

    CourseQueries(CourseRepository courses, CourseVersionRepository versions, CourseAccess access, EntityManager entityManager) {
        this.courses = courses;
        this.versions = versions;
        this.access = access;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Optional<CourseFacts> course(UUID courseId) {
        return courses.findById(courseId).map(c -> new CourseFacts(c.getId(), c.getOwnerOrganizationId(), c.getSlug(),
            c.getTitle(), c.getVisibility(), c.getStatus(), c.getCurrentPublishedVersionId()));
    }

    @Transactional(readOnly = true)
    public Optional<VersionFacts> version(UUID versionId) {
        return versions.findById(versionId).map(v -> new VersionFacts(v.getId(), v.getCourseId(), v.getVersionNumber(), v.getStatus()));
    }

    @Transactional(readOnly = true)
    public Optional<LessonFacts> lesson(UUID lessonId) {
        return entityManager.createQuery("""
                select l.id, l.lessonKey, l.version.id, l.version.courseId, l.preview, l.title
                from Lesson l where l.id = :id
                """, Object[].class)
            .setParameter("id", lessonId)
            .getResultStream()
            .findFirst()
            .map(r -> new LessonFacts((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3], (Boolean) r[4], (String) r[5]));
    }

    @Transactional(readOnly = true)
    public Optional<VersionTree> tree(UUID versionId) {
        return versions.findById(versionId).map(VersionTree::of);
    }

    /** Course staff (any role, including platform support) may see every lesson without an enrollment. */
    @Transactional(readOnly = true)
    public boolean isStaff(CurrentActor actor, UUID courseId) {
        return courses.findById(courseId).flatMap(c -> access.roleOf(actor, c)).isPresent();
    }
}
