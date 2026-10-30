package org.example.ohara.enrollment.application;

import org.example.ohara.auth.application.AccountProvisioning;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.enrollment.api.dto.ExtendGrantRequest;
import org.example.ohara.enrollment.api.dto.GrantReportRow;
import org.example.ohara.enrollment.api.dto.GrantRequest;
import org.example.ohara.enrollment.api.dto.GrantResponse;
import org.example.ohara.enrollment.domain.AccessGrant;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.AccessGrantRepository;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The contract team's tools (ROLE_ENROLLMENT_MANAGER, or platform admins). Payments and refunds happen offline
 * (ADR-012); every grant records the contract number / bank reference it came from, and the report lets finance
 * reconcile access against bank records.
 */
@Service
public class AccessGrantService {

    private static final Set<AccessGrant.RevokedReason> STAFF_REVOKE_REASONS = EnumSet.of(
        AccessGrant.RevokedReason.REFUNDED_OFFLINE, AccessGrant.RevokedReason.CONTRACT_ENDED, AccessGrant.RevokedReason.ADMIN);

    private final CourseOfferingRepository offerings;
    private final EnrollmentRepository enrollments;
    private final AccessGrantRepository grants;
    private final EnrollmentCore core;
    private final AccountProvisioning accounts;
    private final UserService users;
    private final CourseQueries courses;

    public AccessGrantService(CourseOfferingRepository offerings, EnrollmentRepository enrollments, AccessGrantRepository grants,
                              EnrollmentCore core, AccountProvisioning accounts, UserService users, CourseQueries courses) {
        this.offerings = offerings;
        this.enrollments = enrollments;
        this.grants = grants;
        this.core = core;
        this.accounts = accounts;
        this.users = users;
        this.courses = courses;
    }

    /**
     * Grants B2C access after an offline payment. A person without an account gets one (with a set-password email).
     * Submitting the same reference again returns the existing grant; any other live access is a conflict (extend it).
     */
    @Transactional
    public GrantResponse grant(CurrentActor actor, UUID offeringId, GrantRequest r) {
        OfferingService.requireContractTeamOrAdmin(actor);
        CourseOffering offering = offerings.findById(offeringId)
            .filter(o -> o.getChannel() == CourseOffering.Channel.B2C)
            .orElseThrow(OfferingService::offeringNotFound);
        if (!offering.isOpen()) {
            throw new ApiException.Conflict("offering_not_open", "The offering is " + offering.getStatus().name().toLowerCase());
        }
        Instant now = Instant.now();
        Instant until = r.validUntil() != null ? r.validUntil() : offering.accessEnd(now);
        if (until != null && !until.isAfter(now)) {
            throw new ApiException.BadRequest("invalid_window", "validUntil must be in the future");
        }

        boolean userCreated = false;
        Optional<User> existing = accounts.findAccount(r.email());
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (user.getStatus() == UserStatus.ANONYMIZED) {
                throw new ApiException.Unprocessable("account_deleted", "The account for this email was deleted");
            }
        } else {
            if (blank(r.firstName()) || blank(r.lastName())) {
                throw new ApiException.BadRequest("names_required", "firstName and lastName are needed to create the learner's account");
            }
            user = accounts.createAccountWithoutPassword(r.email(), r.firstName().trim(), r.lastName().trim());
            userCreated = true;
        }

        Enrollment enrollment = core.enroll(user.getId(), offering);
        List<AccessGrant> live = grants.findByEnrollmentIdOrderByCreatedAt(enrollment.getId()).stream()
            .filter(g -> g.isLive(now)).toList();
        Optional<AccessGrant> sameReference = live.stream()
            .filter(g -> g.getSource() == AccessGrant.Source.CONTRACT && r.externalReference().equals(g.getExternalReference()))
            .findFirst();
        if (sameReference.isPresent()) {
            return GrantResponse.of(sameReference.get(), user.getEmail(), false, false);
        }
        if (!live.isEmpty()) {
            throw new ApiException.Conflict("already_has_access", "This learner already has access to this offering; extend it instead")
                .with("grantId", live.getFirst().getId());
        }
        AccessGrant grant = core.grant(enrollment, AccessGrant.Source.CONTRACT, null, r.externalReference().trim(),
            r.note(), now, until, actor.userId());
        return GrantResponse.of(grant, user.getEmail(), true, userCreated);
    }

    /** Renewal after another offline payment: a new grant replaces the live one (history keeps both). */
    @Transactional
    public GrantResponse extend(CurrentActor actor, UUID grantId, ExtendGrantRequest r) {
        OfferingService.requireContractTeamOrAdmin(actor);
        AccessGrant current = staffGrant(grantId);
        Instant now = Instant.now();
        if (!current.isLive(now)) {
            throw new ApiException.Conflict("grant_not_live", "Only live grants can be extended; grant access again instead");
        }
        if (!r.validUntil().isAfter(now) || (current.getValidUntil() != null && !r.validUntil().isAfter(current.getValidUntil()))) {
            throw new ApiException.BadRequest("invalid_window", "validUntil must be later than the current end of access");
        }
        Enrollment enrollment = enrollments.findById(current.getEnrollmentId()).orElseThrow();
        AccessGrant renewed = core.grant(enrollment, AccessGrant.Source.CONTRACT, null, r.externalReference().trim(),
            r.note(), now, r.validUntil(), actor.userId());
        core.revoke(current, AccessGrant.RevokedReason.REPLACED, actor.userId());
        return GrantResponse.of(renewed, emailOf(enrollment.getUserId()), true, false);
    }

    /** Refunds and contract ends are recorded here; the money side is handled offline. Progress is kept. */
    @Transactional
    public GrantResponse revoke(CurrentActor actor, UUID grantId, AccessGrant.RevokedReason reason) {
        OfferingService.requireContractTeamOrAdmin(actor);
        if (!STAFF_REVOKE_REASONS.contains(reason)) {
            throw new ApiException.BadRequest("invalid_reason", "Use REFUNDED_OFFLINE, CONTRACT_ENDED or ADMIN");
        }
        AccessGrant grant = staffGrant(grantId);
        if (grant.getRevokedAt() == null) {
            core.revoke(grant, reason, actor.userId());
        }
        return GrantResponse.of(grant, emailOf(grant.getUserId()), false, false);
    }

    @Transactional(readOnly = true)
    public List<GrantReportRow> report(CurrentActor actor, AccessGrant.Source source, Instant from, Instant to) {
        OfferingService.requireContractTeamOrAdmin(actor);
        List<AccessGrant> found = grants.findForReport(source, from, to);
        Set<UUID> peopleIds = new HashSet<>();
        found.forEach(g -> Stream.of(g.getUserId(), g.getGrantedBy(), g.getRevokedBy())
            .filter(java.util.Objects::nonNull).forEach(peopleIds::add));
        Map<UUID, User> people = users.findByIds(peopleIds);
        Map<UUID, Enrollment> enrollmentById = enrollments.findAllById(found.stream().map(AccessGrant::getEnrollmentId).toList())
            .stream().collect(Collectors.toMap(Enrollment::getId, Function.identity()));
        Map<UUID, String> courseTitles = enrollmentById.values().stream().map(Enrollment::getCourseId).distinct()
            .collect(Collectors.toMap(Function.identity(),
                id -> courses.course(id).map(CourseQueries.CourseFacts::title).orElse("")));

        return found.stream().map(g -> {
            Enrollment e = enrollmentById.get(g.getEnrollmentId());
            return new GrantReportRow(g.getId(), g.getCreatedAt(), g.getSource(), g.getExternalReference(),
                email(people, g.getUserId()), courseTitles.get(e.getCourseId()), e.getCourseOfferingId(),
                g.getValidFrom(), g.getValidUntil(), email(people, g.getGrantedBy()), g.getRevokedAt(), g.getRevokedReason(),
                email(people, g.getRevokedBy()), g.getNote());
        }).toList();
    }

    public static String toCsv(List<GrantReportRow> rows) {
        StringBuilder csv = new StringBuilder(
            "grant_id,created_at,source,external_reference,learner_email,course,offering_id,valid_from,valid_until,granted_by,revoked_at,revoked_reason,revoked_by,note\n");
        for (GrantReportRow r : rows) {
            csv.append(String.join(",", Stream.of(r.grantId(), r.createdAt(), r.source(), r.externalReference(),
                    r.learnerEmail(), r.courseTitle(), r.offeringId(), r.validFrom(), r.validUntil(), r.grantedBy(),
                    r.revokedAt(), r.revokedReason(), r.revokedBy(), r.note())
                .map(v -> field(v == null ? "" : v.toString())).toList())).append('\n');
        }
        return csv.toString();
    }

    /** Grants the contract team manages; cohort grants follow cohort membership and are managed by org admins. */
    private AccessGrant staffGrant(UUID grantId) {
        AccessGrant grant = grants.findById(grantId)
            .orElseThrow(() -> new ApiException.NotFound("grant_not_found", "Grant not found"));
        if (grant.getSource() == AccessGrant.Source.COHORT_ASSIGNMENT) {
            throw new ApiException.Conflict("cohort_grant", "Cohort access follows cohort membership; change the placement instead");
        }
        return grant;
    }

    private String emailOf(UUID userId) {
        return users.findById(userId).map(User::getEmail).orElse(null);
    }

    private static String email(Map<UUID, User> people, UUID id) {
        return id == null || !people.containsKey(id) ? null : people.get(id).getEmail();
    }

    private static String field(String value) {
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
