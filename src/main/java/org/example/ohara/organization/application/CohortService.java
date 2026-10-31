package org.example.ohara.organization.application;

import org.example.ohara.platform.audit.AuditLog;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.organization.api.dto.AddCohortMembersResponse;
import org.example.ohara.organization.api.dto.CohortMemberResponse;
import org.example.ohara.organization.api.dto.CohortResponse;
import org.example.ohara.organization.api.dto.CreateCohortRequest;
import org.example.ohara.organization.api.dto.UpdateCohortRequest;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.domain.CohortMembership;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.CohortMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Cohorts and learner placement. Placements are never deleted: leaving or transferring sets left_at. */
@Service
public class CohortService {

    private final CohortRepository cohorts;
    private final CohortMembershipRepository cohortMemberships;
    private final OrganizationMembershipRepository memberships;
    private final OrgAuthorizationService authorization;
    private final CohortPlacementEvents events;
    private final AuditLog audit;

    public CohortService(CohortRepository cohorts, CohortMembershipRepository cohortMemberships,
                         OrganizationMembershipRepository memberships, OrgAuthorizationService authorization,
                         CohortPlacementEvents events, AuditLog audit) {
        this.audit = audit;
        this.cohorts = cohorts;
        this.cohortMemberships = cohortMemberships;
        this.memberships = memberships;
        this.authorization = authorization;
        this.events = events;
    }

    @Transactional
    public CohortResponse create(CurrentActor actor, UUID organizationId, CreateCohortRequest request) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_COHORTS);
        if (cohorts.existsByOrganizationIdAndCode(organizationId, request.code())) {
            throw new ApiException.Conflict("cohort_code_taken", "A cohort with this code already exists");
        }
        Cohort cohort = cohorts.save(new Cohort(organizationId, request.name().trim(), request.code(),
            request.startsOn(), validEnd(request.startsOn(), request.endsOn()), actor.userId()));
        return CohortResponse.of(cohort, 0);
    }

    @Transactional(readOnly = true)
    public List<CohortResponse> list(CurrentActor actor, UUID organizationId, Cohort.Status status) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_COHORTS);
        List<Cohort> found = status == null
            ? cohorts.findByOrganizationIdOrderByStartsOnDescNameAsc(organizationId)
            : cohorts.findByOrganizationIdAndStatusOrderByStartsOnDescNameAsc(organizationId, status);
        Map<UUID, Long> counts = found.isEmpty() ? Map.of() : cohortMemberships.countOpen(found.stream().map(Cohort::getId).toList())
            .stream().collect(Collectors.toMap(CohortMembershipRepository.CohortCount::getCohortId,
                CohortMembershipRepository.CohortCount::getMembers));
        return found.stream().map(c -> CohortResponse.of(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public CohortResponse get(CurrentActor actor, UUID organizationId, UUID cohortId) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_COHORTS);
        Cohort cohort = cohort(organizationId, cohortId);
        return CohortResponse.of(cohort, cohortMemberships.findOpenByCohort(cohortId).size());
    }

    @Transactional
    public CohortResponse update(CurrentActor actor, UUID organizationId, UUID cohortId, UpdateCohortRequest request) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_COHORTS);
        Cohort cohort = cohort(organizationId, cohortId);
        if (request.name() != null) {
            cohort.rename(request.name().trim());
        }
        if (request.startsOn() != null || request.endsOn() != null) {
            var start = request.startsOn() != null ? request.startsOn() : cohort.getStartsOn();
            var end = request.endsOn() != null ? request.endsOn() : cohort.getEndsOn();
            cohort.reschedule(start, validEnd(start, end));
        }
        if (request.status() != null && request.status() != cohort.getStatus()) {
            cohort.changeStatus(request.status());
            if (!cohort.acceptsMembers()) {
                cohortMemberships.findOpenByCohort(cohortId).forEach(cm -> {
                    cm.end(CohortMembership.Status.COMPLETED);
                    events.ended(cm);
                });
            }
        }
        return CohortResponse.of(cohort, cohortMemberships.findOpenByCohort(cohortId).size());
    }

    @Transactional(readOnly = true)
    public List<CohortMemberResponse> members(CurrentActor actor, UUID organizationId, UUID cohortId, boolean includeFormer) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_COHORTS);
        cohort(organizationId, cohortId);
        List<CohortMembership> found = includeFormer
            ? cohortMemberships.findAllByCohort(cohortId)
            : cohortMemberships.findOpenByCohort(cohortId);
        return found.stream().map(CohortMemberResponse::of).toList();
    }

    /** Idempotent: users already placed are reported, not duplicated. Only active LEARNER members are eligible. */
    @Transactional
    public AddCohortMembersResponse addMembers(CurrentActor actor, UUID organizationId, UUID cohortId, Set<UUID> userIds) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_COHORTS);
        Cohort cohort = openCohort(organizationId, cohortId);

        Map<UUID, OrganizationMembership> eligible = memberships.findByOrganizationIdAndUserIdIn(organizationId, userIds).stream()
            .filter(m -> m.isActive() && m.hasRole(OrgRole.LEARNER))
            .collect(Collectors.toMap(m -> m.getUser().getId(), Function.identity()));
        Set<UUID> alreadyPlaced = cohortMemberships.findOpen(cohortId, userIds).stream()
            .map(cm -> cm.getUser().getId()).collect(Collectors.toSet());

        List<UUID> added = new ArrayList<>();
        List<UUID> already = new ArrayList<>();
        List<UUID> notEligible = new ArrayList<>();
        for (UUID userId : userIds) {
            if (alreadyPlaced.contains(userId)) {
                already.add(userId);
            } else if (!eligible.containsKey(userId)) {
                notEligible.add(userId);
            } else {
                events.started(cohortMemberships.save(new CohortMembership(cohort, eligible.get(userId).getUser())));
                added.add(userId);
            }
        }
        return new AddCohortMembersResponse(added, already, notEligible);
    }

    @Transactional
    public void removeMember(CurrentActor actor, UUID organizationId, UUID cohortId, UUID userId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_COHORTS);
        cohort(organizationId, cohortId);
        CohortMembership placement = cohortMemberships.findOpen(cohortId, userId).orElseThrow(CohortService::cohortMemberNotFound);
        placement.end(CohortMembership.Status.REMOVED);
        events.ended(placement);
        audit.record(actor.userId(), organizationId, "COHORT_MEMBER_REMOVED", "COHORT_PLACEMENT", placement.getId(),
            AuditLog.details("cohortId", cohortId, "userId", userId));
    }

    @Transactional
    public CohortMemberResponse transfer(CurrentActor actor, UUID organizationId, UUID cohortId, UUID userId, UUID toCohortId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_COHORTS);
        if (cohortId.equals(toCohortId)) {
            throw new ApiException.BadRequest("same_cohort", "Source and target cohort are the same");
        }
        cohort(organizationId, cohortId);
        Cohort target = openCohort(organizationId, toCohortId);
        CohortMembership current = cohortMemberships.findOpen(cohortId, userId).orElseThrow(CohortService::cohortMemberNotFound);
        if (cohortMemberships.findOpen(toCohortId, userId).isPresent()) {
            throw new ApiException.Conflict("already_in_cohort", "The learner is already in the target cohort");
        }
        current.transferTo(toCohortId);
        // Flush the ended placement first so the open-placement unique index never sees two open rows mid-transaction.
        cohortMemberships.saveAndFlush(current);
        events.ended(current);
        CohortMembership moved = cohortMemberships.save(new CohortMembership(target, current.getUser()));
        events.started(moved);
        audit.record(actor.userId(), organizationId, "COHORT_MEMBER_TRANSFERRED", "COHORT_PLACEMENT", moved.getId(),
            AuditLog.details("fromCohortId", cohortId, "toCohortId", toCohortId, "userId", userId));
        return CohortMemberResponse.of(moved);
    }

    private Cohort cohort(UUID organizationId, UUID cohortId) {
        return cohorts.findByIdAndOrganizationId(cohortId, organizationId)
            .orElseThrow(() -> new ApiException.NotFound("cohort_not_found", "Cohort not found"));
    }

    private Cohort openCohort(UUID organizationId, UUID cohortId) {
        Cohort cohort = cohort(organizationId, cohortId);
        if (!cohort.acceptsMembers()) {
            throw new ApiException.Conflict("cohort_closed", "The cohort is " + cohort.getStatus().name().toLowerCase());
        }
        return cohort;
    }

    private static LocalDate validEnd(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException.BadRequest("invalid_dates", "endsOn must not be before startsOn");
        }
        return end;
    }

    private static ApiException cohortMemberNotFound() {
        return new ApiException.NotFound("cohort_member_not_found", "The user is not in this cohort");
    }
}
