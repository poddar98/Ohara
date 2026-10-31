package org.example.ohara.organization.application;

import org.example.ohara.platform.audit.AuditLog;
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
    private final AuditLog audit;
    private final OrganizationMembershipRepository memberships;

    public OrgAuthorizationService(OrganizationRepository organizations, OrganizationMembershipRepository memberships, AuditLog audit) {
        this.audit = audit;
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
            .orElse(null);
        if (membership == null) {
            // Someone outside the organization reached for it: answered as "not found", recorded for review.
            audit.recordIndependently(actor.userId(), organizationId, "ACCESS_DENIED_CROSS_TENANT", "ORGANIZATION", organizationId,
                AuditLog.details("permission", permission.name()));
            throw notFound();
        }

        if (!organization.isActive()) {
            throw new ApiException.Forbidden("organization_inactive", "This organization is " + organization.getStatus().name().toLowerCase());
        }
        if (!permission.isGrantedToAny(membership.getRoles())) {
            throw new ApiException.Forbidden("forbidden", "You are not allowed to perform this action");
        }
        return new OrgAccess(organizationId, actor.userId(), membership.getRoles(), false);
    }

    /** Non-throwing variant of {@link #require}: platform staff rules included. */
    @Transactional(readOnly = true)
    public boolean allows(CurrentActor actor, UUID organizationId, OrgPermission permission) {
        if (actor.isPlatformAdmin() || (actor.isPlatformSupport() && permission.isReadOnly())) {
            return organizations.existsById(organizationId);
        }
        return userHas(organizationId, actor.userId(), permission);
    }

    /** Whether {@code userId} (not necessarily the caller) is an active member of an active org with the permission. */
    @Transactional(readOnly = true)
    public boolean userHas(UUID organizationId, UUID userId, OrgPermission permission) {
        return organizations.findById(organizationId).filter(Organization::isActive).isPresent()
            && memberships.findByOrganizationIdAndUserId(organizationId, userId)
                .filter(m -> m.isActive() && permission.isGrantedToAny(m.getRoles()))
                .isPresent();
    }

    static ApiException notFound() {
        return new ApiException.NotFound("organization_not_found", "Organization not found");
    }
}
