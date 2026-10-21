package org.example.ohara.organization.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.organization.domain.Organization;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.OrganizationMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * The one place that decides whether an actor may act inside an organization. Every org-scoped endpoint calls
 * {@link #require} before touching tenant data.
 *
 * <p>Non-members get 404 rather than 403 so ids of other tenants can't be probed. Members of a suspended or
 * archived organization get 403. Platform admins may do everything; platform support may only read.
 */
@Service
public class OrgAuthorizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;

    public OrgAuthorizationService(OrganizationRepository organizations, OrganizationMembershipRepository memberships) {
        this.organizations = organizations;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public OrgAccess require(CurrentActor actor, UUID organizationId, OrgPermission permission) {
        Organization organization = organizations.findById(organizationId).orElseThrow(OrgAuthorizationService::notFound);

        if (actor.isPlatformAdmin() || (actor.isPlatformSupport() && permission.isReadOnly())) {
            return new OrgAccess(organizationId, actor.userId(), Set.of(), true);
        }

        OrganizationMembership membership = memberships.findByOrganizationIdAndUserId(organizationId, actor.userId())
            .filter(OrganizationMembership::isActive)
            .orElseThrow(OrgAuthorizationService::notFound);

        if (!organization.isActive()) {
            throw new ApiException.Forbidden("organization_inactive", "This organization is " + organization.getStatus().name().toLowerCase());
        }
        if (!permission.isGrantedToAny(membership.getRoles())) {
            throw new ApiException.Forbidden("forbidden", "You are not allowed to perform this action");
        }
        return new OrgAccess(organizationId, actor.userId(), membership.getRoles(), false);
    }

    static ApiException notFound() {
        return new ApiException.NotFound("organization_not_found", "Organization not found");
    }
}
