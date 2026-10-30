package org.example.ohara.organization.application;

import org.example.ohara.organization.domain.CohortMembership;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes cohort placement changes. Listeners (enrollment) run synchronously in the same transaction, so a
 * placement and the access it grants or removes always commit together.
 */
@Component
public class CohortPlacementEvents {

    /** A learner was placed in a cohort. */
    public record Started(UUID organizationId, UUID cohortId, UUID userId, UUID placementId) {
    }

    /** A placement ended; {@code reason} is REMOVED, TRANSFERRED or COMPLETED. */
    public record Ended(UUID organizationId, UUID cohortId, UUID userId, UUID placementId, String reason) {
    }

    private final ApplicationEventPublisher publisher;

    CohortPlacementEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    void started(CohortMembership placement) {
        publisher.publishEvent(new Started(placement.getOrganizationId(), placement.getCohortId(),
            placement.getUser().getId(), placement.getId()));
    }

    void ended(CohortMembership placement) {
        publisher.publishEvent(new Ended(placement.getOrganizationId(), placement.getCohortId(),
            placement.getUser().getId(), placement.getId(), placement.getStatus().name()));
    }
}
