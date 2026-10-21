package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.OrgRole;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MyOrganizationResponse(OrganizationResponse organization, Set<OrgRole> roles, List<CohortSummary> cohorts) {

    public record CohortSummary(UUID id, String name, String code) {
    }
}
