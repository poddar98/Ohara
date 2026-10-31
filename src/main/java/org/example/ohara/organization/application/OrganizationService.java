package org.example.ohara.organization.application;

import org.example.ohara.platform.audit.AuditLog;
import org.example.ohara.platform.audit.AuditQueries;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.AddMemberRequest;
import org.example.ohara.organization.api.dto.CreateOrganizationRequest;
import org.example.ohara.organization.api.dto.MemberResponse;
import org.example.ohara.organization.api.dto.MyOrganizationResponse;
import org.example.ohara.organization.api.dto.OrganizationResponse;
import org.example.ohara.organization.api.dto.UpdateMemberRequest;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.domain.CohortMembership;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.Organization;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.CohortMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final CohortRepository cohorts;
    private final CohortMembershipRepository cohortMemberships;
    private final OrgAuthorizationService authorization;
    private final UserService userService;
    private final CohortPlacementEvents events;
    private final AuditLog audit;
    private final AuditQueries auditQueries;

    public OrganizationService(OrganizationRepository organizations,
                               OrganizationMembershipRepository memberships,
                               CohortRepository cohorts,
                               CohortMembershipRepository cohortMemberships,
                               OrgAuthorizationService authorization,
                               UserService userService,
                               CohortPlacementEvents events, AuditLog audit, AuditQueries auditQueries) {
        this.audit = audit;
        this.auditQueries = auditQueries;
        this.organizations = organizations;
        this.memberships = memberships;
        this.cohorts = cohorts;
        this.cohortMemberships = cohortMemberships;
        this.authorization = authorization;
        this.userService = userService;
        this.events = events;
    }

    // --- platform administration -------------------------------------------------------------------

    @Transactional
    public OrganizationResponse create(CurrentActor actor, CreateOrganizationRequest request) {
        requirePlatformAdmin(actor);
        if (organizations.existsBySlug(request.slug())) {
            throw new ApiException.Conflict("slug_taken", "An organization with this slug already exists");
        }
        Organization org = organizations.save(new Organization(request.name().trim(), request.slug(), request.type(), actor.userId()));
        return OrganizationResponse.of(org);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> listAll(CurrentActor actor, Organization.Status status, int page, int size) {
        if (!actor.isPlatformAdmin() && !actor.isPlatformSupport()) {
            throw forbidden();
        }
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size), Sort.by("name"));
        Page<Organization> result = status == null ? organizations.findAll(pageRequest) : organizations.findByStatus(status, pageRequest);
        return PageResponse.of(result, OrganizationResponse::of);
    }

    /** Suspending or archiving blocks every member (not platform staff) immediately; data is kept. */
    @Transactional
    public OrganizationResponse changeStatus(CurrentActor actor, UUID organizationId, Organization.Status status) {
        requirePlatformAdmin(actor);
        Organization org = organizations.findById(organizationId).orElseThrow(OrgAuthorizationService::notFound);
        Organization.Status previous = org.getStatus();
        org.changeStatus(status);
        if (previous != status) {
            audit.record(actor.userId(), organizationId, "ORGANIZATION_STATUS_CHANGED", "ORGANIZATION", organizationId,
                AuditLog.details("from", previous.name(), "to", status.name()));
        }
        return OrganizationResponse.of(org);
    }

    // --- organization ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrganizationResponse get(CurrentActor actor, UUID organizationId) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_ORG);
        return OrganizationResponse.of(organizations.getReferenceById(organizationId));
    }

    @Transactional
    public OrganizationResponse rename(CurrentActor actor, UUID organizationId, String name) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_ORG);
        Organization org = organizations.getReferenceById(organizationId);
        org.rename(name.trim());
        return OrganizationResponse.of(org);
    }

    // --- members -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> listMembers(CurrentActor actor, UUID organizationId, OrgRole role,
                                                    OrganizationMembership.Status status, int page, int size) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        Set<OrganizationMembership.Status> statuses = status != null
            ? EnumSet.of(status)
            : EnumSet.of(OrganizationMembership.Status.ACTIVE, OrganizationMembership.Status.SUSPENDED);
        Page<OrganizationMembership> result = memberships.search(
            organizationId, statuses, role, PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size)));
        return PageResponse.of(result, MemberResponse::of);
    }

    public record AddMemberResult(MemberResponse member, boolean created) {
    }

    /**
     * Adds an existing account to the organization, or adds roles to an existing member. A previously removed member
     * is re-activated with exactly the requested roles. People without an account will go through invitations.
     */
    @Transactional
    public AddMemberResult addMember(CurrentActor actor, UUID organizationId, AddMemberRequest request) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        User user = userService.findByEmail(request.email().trim())
            .filter(u -> u.getStatus() != UserStatus.ANONYMIZED)
            .orElseThrow(() -> new ApiException.Unprocessable("user_not_registered",
                "No account exists for this email; invite the person instead"));
        String externalRef = blankToNull(request.externalRef());
        requireUniqueExternalRef(organizationId, externalRef, user.getId());

        Optional<OrganizationMembership> existing = memberships.findByOrganizationIdAndUserId(organizationId, user.getId());
        if (existing.isEmpty()) {
            OrganizationMembership created = memberships.save(
                new OrganizationMembership(organizationId, user, request.roles(), externalRef));
            audit.record(actor.userId(), organizationId, "MEMBER_ADDED", "MEMBERSHIP", user.getId(),
                AuditLog.details("roles", created.getRoles()));
            return new AddMemberResult(MemberResponse.of(created), true);
        }

        OrganizationMembership membership = existing.get();
        if (membership.getStatus() == OrganizationMembership.Status.REMOVED) {
            membership.activate();
            membership.replaceRoles(request.roles());
        } else {
            membership.addRoles(request.roles());
        }
        if (externalRef != null) {
            membership.setExternalRef(externalRef);
        }
        audit.record(actor.userId(), organizationId, "MEMBER_ROLES_CHANGED", "MEMBERSHIP", user.getId(),
            AuditLog.details("roles", membership.getRoles(), "status", membership.getStatus().name()));
        return new AddMemberResult(MemberResponse.of(membership), false);
    }

    @Transactional
    public MemberResponse updateMember(CurrentActor actor, UUID organizationId, UUID userId, UpdateMemberRequest request) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        OrganizationMembership membership = memberships.findByOrganizationIdAndUserId(organizationId, userId)
            .orElseThrow(OrganizationService::memberNotFound);

        Set<OrgRole> oldRoles = EnumSet.copyOf(membership.getRoles());
        OrganizationMembership.Status oldStatus = membership.getStatus();
        Set<OrgRole> newRoles = request.roles() != null ? EnumSet.copyOf(request.roles()) : membership.getRoles();
        OrganizationMembership.Status newStatus = request.status() != null ? request.status() : membership.getStatus();
        guardLastAdmin(organizationId, membership, newRoles, newStatus);

        if (request.externalRef() != null) {
            String externalRef = blankToNull(request.externalRef());
            requireUniqueExternalRef(organizationId, externalRef, userId);
            membership.setExternalRef(externalRef);
        }
        if (request.roles() != null) {
            membership.replaceRoles(newRoles);
        }
        if (request.status() != null && request.status() != membership.getStatus()) {
            switch (request.status()) {
                case ACTIVE -> membership.activate();
                case SUSPENDED -> membership.suspend();
                case REMOVED -> membership.remove();
            }
        }
        // Cohort placement requires being an active learner; end placements that no longer qualify.
        if (newStatus == OrganizationMembership.Status.REMOVED || !newRoles.contains(OrgRole.LEARNER)) {
            cohortMemberships.findOpenInOrganization(organizationId, userId)
                .forEach(cm -> {
                    cm.end(CohortMembership.Status.REMOVED);
                    events.ended(cm);
                });
        }
        if (!oldRoles.equals(membership.getRoles()) || oldStatus != membership.getStatus()) {
            audit.record(actor.userId(), organizationId, "MEMBER_ROLES_CHANGED", "MEMBERSHIP", userId, AuditLog.details(
                "fromRoles", oldRoles, "toRoles", membership.getRoles(), "fromStatus", oldStatus.name(),
                "toStatus", membership.getStatus().name()));
        }
        return MemberResponse.of(membership);
    }

    /** The organization's audit trail, newest first. Org admins and platform staff. */
    @Transactional(readOnly = true)
    public PageResponse<AuditQueries.Event> audit(CurrentActor actor, UUID organizationId, UUID actorUserId, String action,
                                                  String resourceType, String resourceId, Instant from, Instant to, int page, int size) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_AUDIT);
        return auditQueries.page(new AuditQueries.Filter(organizationId, actorUserId, action, resourceType, resourceId, from, to),
            Math.max(page, 0), PageResponse.clampSize(size));
    }

    /** The authenticated user's active affiliations, with their open cohort placements. */
    @Transactional(readOnly = true)
    public List<MyOrganizationResponse> myOrganizations(CurrentActor actor) {
        List<OrganizationMembership> mine = memberships.findByUserIdAndStatus(actor.userId(), OrganizationMembership.Status.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<UUID, Organization> orgsById = organizations.findAllById(mine.stream().map(OrganizationMembership::getOrganizationId).toList())
            .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));
        List<CohortMembership> placements = cohortMemberships.findOpenByUser(actor.userId());
        Map<UUID, Cohort> cohortsById = cohorts.findByIdIn(placements.stream().map(CohortMembership::getCohortId).toList())
            .stream().collect(Collectors.toMap(Cohort::getId, Function.identity()));

        return mine.stream()
            .filter(m -> orgsById.get(m.getOrganizationId()).isActive())
            .map(m -> new MyOrganizationResponse(
                OrganizationResponse.of(orgsById.get(m.getOrganizationId())),
                new TreeSet<>(m.getRoles()),
                placements.stream()
                    .map(cm -> cohortsById.get(cm.getCohortId()))
                    .filter(c -> c.getOrganizationId().equals(m.getOrganizationId()))
                    .map(c -> new MyOrganizationResponse.CohortSummary(c.getId(), c.getName(), c.getCode()))
                    .toList()))
            .toList();
    }

    /** An organization must always keep at least one active admin. */
    private void guardLastAdmin(UUID organizationId, OrganizationMembership membership,
                                Set<OrgRole> newRoles, OrganizationMembership.Status newStatus) {
        boolean isActiveAdmin = membership.isActive() && membership.hasRole(OrgRole.ORG_ADMIN);
        boolean staysActiveAdmin = newStatus == OrganizationMembership.Status.ACTIVE && newRoles.contains(OrgRole.ORG_ADMIN);
        if (isActiveAdmin && !staysActiveAdmin && memberships.countActiveWithRole(organizationId, OrgRole.ORG_ADMIN) <= 1) {
            throw new ApiException.Conflict("last_org_admin", "An organization must keep at least one active admin");
        }
    }

    private void requireUniqueExternalRef(UUID organizationId, String externalRef, UUID userId) {
        if (externalRef != null && memberships.existsByOrganizationIdAndExternalRefAndUserIdNot(organizationId, externalRef, userId)) {
            throw new ApiException.Conflict("external_ref_taken", "Another member already has this reference");
        }
    }

    private static void requirePlatformAdmin(CurrentActor actor) {
        if (!actor.isPlatformAdmin()) {
            throw forbidden();
        }
    }

    private static ApiException forbidden() {
        return new ApiException.Forbidden("forbidden", "You are not allowed to perform this action");
    }

    static ApiException memberNotFound() {
        return new ApiException.NotFound("member_not_found", "Member not found");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
