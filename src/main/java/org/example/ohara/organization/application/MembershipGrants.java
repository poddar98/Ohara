package org.example.ohara.organization.application;

import org.example.ohara.common.web.ApiException;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.domain.CohortMembership;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.CohortMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationMembershipRepository;
import org.example.ohara.user.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Idempotent "make this person a member with this role, and place them in this cohort" used by invitations and imports. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MembershipGrants {

    private final OrganizationMembershipRepository memberships;
    private final CohortMembershipRepository cohortMemberships;

    MembershipGrants(OrganizationMembershipRepository memberships, CohortMembershipRepository cohortMemberships) {
        this.memberships = memberships;
        this.cohortMemberships = cohortMemberships;
    }

    Optional<OrganizationMembership> membership(UUID organizationId, UUID userId) {
        return memberships.findByOrganizationIdAndUserId(organizationId, userId);
    }

    boolean isPlaced(UUID cohortId, UUID userId) {
        return cohortMemberships.findOpen(cohortId, userId).isPresent();
    }

    /** True if the user already is an active member holding {@code role}. */
    boolean holdsRole(UUID organizationId, UUID userId, OrgRole role) {
        return memberships.findByOrganizationIdAndUserId(organizationId, userId)
            .filter(m -> m.isActive() && m.hasRole(role))
            .isPresent();
    }

    /**
     * Creates the membership, re-activates a removed one, or adds the role to an existing one.
     * A suspended member stays suspended: an invitation must not lift a suspension.
     */
    OrganizationMembership grant(UUID organizationId, User user, OrgRole role, String externalRef) {
        if (externalRef != null
            && memberships.existsByOrganizationIdAndExternalRefAndUserIdNot(organizationId, externalRef, user.getId())) {
            throw new ApiException.Conflict("external_ref_taken", "Another member already has reference " + externalRef);
        }
        OrganizationMembership membership = memberships.findByOrganizationIdAndUserId(organizationId, user.getId())
            .orElse(null);
        if (membership == null) {
            return memberships.save(new OrganizationMembership(organizationId, user, EnumSet.of(role), externalRef));
        }
        if (membership.getStatus() == OrganizationMembership.Status.SUSPENDED) {
            throw new ApiException.Conflict("member_suspended", "This person is suspended in the organization");
        }
        if (membership.getStatus() == OrganizationMembership.Status.REMOVED) {
            membership.activate();
            membership.replaceRoles(Set.of(role));
        } else {
            membership.addRoles(Set.of(role));
        }
        if (externalRef != null) {
            membership.setExternalRef(externalRef);
        }
        return membership;
    }

    /** @return false if the user already has an open placement in the cohort, or the cohort no longer accepts members */
    boolean place(Cohort cohort, User user) {
        if (!cohort.acceptsMembers() || cohortMemberships.findOpen(cohort.getId(), user.getId()).isPresent()) {
            return false;
        }
        cohortMemberships.save(new CohortMembership(cohort, user));
        return true;
    }
}
