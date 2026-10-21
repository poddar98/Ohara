package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.Organization;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String slug, Organization.Type type,
                                   Organization.Status status, Instant createdAt) {

    public static OrganizationResponse of(Organization org) {
        return new OrganizationResponse(org.getId(), org.getName(), org.getSlug(), org.getType(), org.getStatus(), org.getCreatedAt());
    }
}
