package org.example.ohara.enrollment.application;

import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.enrollment.domain.AccessGrant;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.AccessGrantRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Enroll / grant / revoke primitives shared by cohort fan-out, offering admin and the contract team. All idempotent. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class EnrollmentCore {

    private final EnrollmentRepository enrollments;
    private final AccessGrantRepository grants;
    private final CourseQueries courses;

    EnrollmentCore(EnrollmentRepository enrollments, AccessGrantRepository grants, CourseQueries courses) {
        this.enrollments = enrollments;
        this.grants = grants;
        this.courses = courses;
    }

    /** One enrollment per (user, offering); the course version is fixed when it's first created. */
    Enrollment enroll(UUID userId, CourseOffering offering) {
        return enrollments.findByUserIdAndCourseOfferingId(userId, offering.getId())
            .orElseGet(() -> enrollments.save(new Enrollment(userId, offering, versionFor(offering))));
    }

    UUID versionFor(CourseOffering offering) {
        if (offering.getVersionPolicy() == CourseOffering.VersionPolicy.PINNED) {
            return offering.getPinnedCourseVersionId();
        }
        UUID current = courses.course(offering.getCourseId())
            .map(CourseQueries.CourseFacts::currentPublishedVersionId).orElse(null);
        if (current == null) {
            throw new ApiException.Conflict("course_not_published", "The course has no published version yet");
        }
        return current;
    }

    /** Grants tied to a source record (a cohort placement) are created once; retries return the existing grant. */
    AccessGrant grant(Enrollment enrollment, AccessGrant.Source source, UUID sourceRefId, String externalReference,
                      String note, Instant from, Instant until, UUID grantedBy) {
        if (sourceRefId != null) {
            var existing = grants.findByEnrollmentIdAndSourceAndSourceRefId(enrollment.getId(), source, sourceRefId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        AccessGrant grant = grants.save(new AccessGrant(enrollment, source, sourceRefId, externalReference, note, from, until, grantedBy));
        enrollment.reactivate();
        return grant;
    }

    /** Revokes and, if nothing live remains, ends the enrollment (refunds → CANCELLED, otherwise REVOKED). */
    void revoke(AccessGrant grant, AccessGrant.RevokedReason reason, UUID by) {
        grant.revoke(reason, by);
        Enrollment enrollment = enrollments.findById(grant.getEnrollmentId()).orElseThrow();
        Instant now = Instant.now();
        boolean anyLive = grants.findByEnrollmentIdOrderByCreatedAt(enrollment.getId()).stream().anyMatch(g -> g.isLive(now));
        if (!anyLive && reason != AccessGrant.RevokedReason.REPLACED) {
            enrollment.accessEnded(reason == AccessGrant.RevokedReason.REFUNDED_OFFLINE
                ? Enrollment.Status.CANCELLED : Enrollment.Status.REVOKED);
        }
    }
}
