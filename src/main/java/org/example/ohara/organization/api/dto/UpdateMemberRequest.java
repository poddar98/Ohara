package org.example.ohara.organization.api.dto;

import jakarta.validation.constraints.Size;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;

import java.util.Set;

/** Any field left null is unchanged. {@code roles} replaces the whole set and must not be empty. */
public record UpdateMemberRequest(
    @Size(min = 1, message = "A member needs at least one role; remove the member instead") Set<OrgRole> roles,
    OrganizationMembership.Status status,
    @Size(max = 64) String externalRef
) {
}
