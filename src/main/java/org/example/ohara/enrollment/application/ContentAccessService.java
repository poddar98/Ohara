package org.example.ohara.enrollment.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.course.application.CourseQueries.LessonFacts;
import org.example.ohara.enrollment.domain.AccessGrant;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.AccessGrantRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.example.ohara.organization.application.OrganizationQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The single answer to "may this person see this lesson now?" (docs 05 §6). Rules, first match wins:
 * <ol>
 *   <li>course staff (incl. platform admins/support) — always;</li>
 *   <li>a free-preview lesson of the course's current published version — any signed-in user;</li>
 *   <li>an enrollment in the course that is ACTIVE or COMPLETED, pinned to the lesson's version, with an access grant
 *       valid right now, and — for a college offering — the college still active.</li>
 * </ol>
 * Anything else is denied. Callers must not decide access themselves.
 */
@Service
public class ContentAccessService {

    public enum Reason { STAFF, PREVIEW, ENROLLED, NOT_FOUND, NOT_ENROLLED, NO_ACTIVE_ACCESS, ORGANIZATION_INACTIVE }

    /** {@code enrollmentId} is set when access comes from an enrollment. */
    public record Decision(boolean allowed, Reason reason, UUID enrollmentId) {

        static Decision allow(Reason reason, UUID enrollmentId) {
            return new Decision(true, reason, enrollmentId);
        }

        static Decision deny(Reason reason) {
            return new Decision(false, reason, null);
        }
    }

    private final CourseQueries courses;
    private final EnrollmentRepository enrollments;
    private final AccessGrantRepository grants;
    private final OrganizationQueries organizations;

    public ContentAccessService(CourseQueries courses, EnrollmentRepository enrollments, AccessGrantRepository grants,
                                OrganizationQueries organizations) {
        this.courses = courses;
        this.enrollments = enrollments;
        this.grants = grants;
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public Decision lessonAccess(CurrentActor actor, UUID lessonId) {
        Optional<LessonFacts> found = courses.lesson(lessonId);
        if (found.isEmpty()) {
            return Decision.deny(Reason.NOT_FOUND);
        }
        LessonFacts lesson = found.get();
        if (courses.isStaff(actor, lesson.courseId())) {
            return Decision.allow(Reason.STAFF, null);
        }
        if (lesson.preview() && courses.course(lesson.courseId())
                .map(c -> lesson.versionId().equals(c.currentPublishedVersionId())).orElse(false)) {
            return Decision.allow(Reason.PREVIEW, null);
        }
        List<Enrollment> candidates = enrollments.findByUserIdAndCourseId(actor.userId(), lesson.courseId()).stream()
            .filter(e -> e.getCourseVersionId().equals(lesson.versionId()))
            .toList();
        if (candidates.isEmpty()) {
            return Decision.deny(Reason.NOT_ENROLLED);
        }
        Reason denial = Reason.NO_ACTIVE_ACCESS;
        for (Enrollment enrollment : candidates) {
            Decision decision = enrollmentAccess(enrollment);
            if (decision.allowed()) {
                return decision;
            }
            if (decision.reason() == Reason.ORGANIZATION_INACTIVE) {
                denial = Reason.ORGANIZATION_INACTIVE;
            }
        }
        return Decision.deny(denial);
    }

    /**
     * Rule 3 alone, for a whole offering rather than a lesson (live classes, cohort-wide resources): the caller's
     * enrollment in that offering, with an active grant now and an active organization. Staff rules are the caller's.
     */
    @Transactional(readOnly = true)
    public Decision offeringAccess(CurrentActor actor, UUID offeringId) {
        return enrollments.findByUserIdAndCourseOfferingId(actor.userId(), offeringId)
            .map(this::enrollmentAccess)
            .orElse(Decision.deny(Reason.NOT_ENROLLED));
    }

    /** Whether the enrollment grants access right now (used for lock flags and "my courses"). */
    @Transactional(readOnly = true)
    public Decision enrollmentAccess(Enrollment enrollment) {
        if (!enrollment.isStudying()) {
            return Decision.deny(Reason.NO_ACTIVE_ACCESS);
        }
        if (enrollment.getOrganizationId() != null && !organizations.isActive(enrollment.getOrganizationId())) {
            return Decision.deny(Reason.ORGANIZATION_INACTIVE);
        }
        Instant now = Instant.now();
        boolean granted = grants.findByEnrollmentIdOrderByCreatedAt(enrollment.getId()).stream().anyMatch(g -> g.isActiveAt(now));
        return granted ? Decision.allow(Reason.ENROLLED, enrollment.getId()) : Decision.deny(Reason.NO_ACTIVE_ACCESS);
    }

    @Transactional(readOnly = true)
    public Instant accessUntil(Enrollment enrollment) {
        List<AccessGrant> all = grants.findByEnrollmentIdOrderByCreatedAt(enrollment.getId());
        return OfferingService.accessUntil(all, Instant.now());
    }
}
