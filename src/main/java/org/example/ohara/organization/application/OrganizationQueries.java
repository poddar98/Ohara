package org.example.ohara.organization.application;

import org.example.ohara.organization.domain.Organization;
import org.example.ohara.organization.infrastructure.persistence.CohortMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only facts other modules need about organizations and cohorts. Callers do their own authorization. */
@Service
public class OrganizationQueries {

    public record Placement(UUID placementId, UUID userId) {
    }

    private final OrganizationRepository organizations;
    private final CohortRepository cohorts;
    private final CohortMembershipRepository placements;

    OrganizationQueries(OrganizationRepository organizations, CohortRepository cohorts, CohortMembershipRepository placements) {
        this.organizations = organizations;
        this.cohorts = cohorts;
        this.placements = placements;
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID organizationId) {
        return organizations.findById(organizationId).map(Organization::isActive).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean cohortBelongsTo(UUID cohortId, UUID organizationId) {
        return cohorts.findByIdAndOrganizationId(cohortId, organizationId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Placement> openPlacements(UUID cohortId) {
        return placements.findOpenByCohort(cohortId).stream()
            .map(cm -> new Placement(cm.getId(), cm.getUser().getId()))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<String> organizationName(UUID organizationId) {
        return organizations.findById(organizationId).map(Organization::getName);
    }
}
