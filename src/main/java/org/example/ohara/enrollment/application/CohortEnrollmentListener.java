package org.example.ohara.enrollment.application;

import org.example.ohara.enrollment.domain.AccessGrant;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.infrastructure.persistence.AccessGrantRepository;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.organization.application.CohortPlacementEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Keeps B2B access in step with cohort placements, inside the same transaction as the placement change:
 * joining a cohort enrolls the learner in every open offering of that cohort; leaving or transferring revokes the
 * access that placement gave. Completing a cohort keeps access — the offering's window decides when it ends.
 */
@Component
class CohortEnrollmentListener {

    private final CourseOfferingRepository offerings;
    private final AccessGrantRepository grants;
    private final EnrollmentCore core;

    CohortEnrollmentListener(CourseOfferingRepository offerings, AccessGrantRepository grants, EnrollmentCore core) {
        this.offerings = offerings;
        this.grants = grants;
        this.core = core;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(CohortPlacementEvents.Started event) {
        Instant now = Instant.now();
        for (CourseOffering offering : offerings.findByCohortIdAndStatus(event.cohortId(), CourseOffering.Status.OPEN)) {
            var enrollment = core.enroll(event.userId(), offering);
            core.grant(enrollment, AccessGrant.Source.COHORT_ASSIGNMENT, event.placementId(), null, null,
                offering.accessStart(now), offering.accessEnd(now), null);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(CohortPlacementEvents.Ended event) {
        AccessGrant.RevokedReason reason = switch (event.reason()) {
            case "TRANSFERRED" -> AccessGrant.RevokedReason.TRANSFERRED;
            case "REMOVED" -> AccessGrant.RevokedReason.REMOVED_FROM_COHORT;
            default -> null;
        };
        if (reason == null) {
            return;
        }
        grants.findBySourceRefIdAndRevokedAtIsNull(event.placementId()).forEach(g -> core.revoke(g, reason, null));
    }
}
