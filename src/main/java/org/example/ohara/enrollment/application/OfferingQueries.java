package org.example.ohara.enrollment.application;

import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only facts other modules need about offerings and who may study them. Callers do their own authorization. */
@Service
public class OfferingQueries {

    public record OfferingFacts(UUID id, UUID courseId, boolean b2b, UUID organizationId, UUID cohortId, boolean schedulable) {
    }

    private final CourseOfferingRepository offerings;
    private final EnrollmentRepository enrollments;
    private final ContentAccessService access;

    OfferingQueries(CourseOfferingRepository offerings, EnrollmentRepository enrollments, ContentAccessService access) {
        this.offerings = offerings;
        this.enrollments = enrollments;
        this.access = access;
    }

    /** {@code schedulable}: DRAFT or OPEN, i.e. teaching may still be planned against it. */
    @Transactional(readOnly = true)
    public Optional<OfferingFacts> offering(UUID offeringId) {
        return offerings.findById(offeringId).map(o -> new OfferingFacts(o.getId(), o.getCourseId(),
            o.getChannel() == CourseOffering.Channel.B2B, o.getOrganizationId(), o.getCohortId(),
            o.getStatus() == CourseOffering.Status.DRAFT || o.getStatus() == CourseOffering.Status.OPEN));
    }

    /** Offerings the user may study right now (the same rule as {@link ContentAccessService#enrollmentAccess}). */
    @Transactional(readOnly = true)
    public List<UUID> accessibleOfferings(UUID userId) {
        return enrollments.findByUserIdOrderByEnrolledAtDesc(userId).stream()
            .filter(e -> access.enrollmentAccess(e).allowed())
            .map(Enrollment::getCourseOfferingId)
            .distinct()
            .toList();
    }

    /** Learners who may study the offering right now. */
    @Transactional(readOnly = true)
    public List<UUID> learnersWithAccess(UUID offeringId) {
        return enrollments.findByCourseOfferingIdOrderByEnrolledAt(offeringId).stream()
            .filter(e -> access.enrollmentAccess(e).allowed())
            .map(Enrollment::getUserId)
            .toList();
    }
}
