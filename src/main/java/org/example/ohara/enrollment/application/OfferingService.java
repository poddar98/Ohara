package org.example.ohara.enrollment.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.course.application.CourseQueries.CourseFacts;
import org.example.ohara.course.domain.Course;
import org.example.ohara.course.domain.CourseVersion;
import org.example.ohara.enrollment.api.dto.CreateB2BOfferingRequest;
import org.example.ohara.enrollment.api.dto.CreateB2COfferingRequest;
import org.example.ohara.enrollment.api.dto.OfferingEnrollmentRow;
import org.example.ohara.enrollment.api.dto.OfferingResponse;
import org.example.ohara.enrollment.api.dto.UpdateOfferingRequest;
import org.example.ohara.enrollment.domain.AccessGrant;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.AccessGrantRepository;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.example.ohara.organization.application.OrganizationQueries;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Offerings. B2B: an organization's admins offer the organization's own courses to its cohorts; offering a platform
 * course to an organization is a platform-admin action (it stands in for a licence, Q-03). B2C: platform admins list
 * platform courses publicly; the contract team grants access (see {@link AccessGrantService}).
 */
@Service
public class OfferingService {

    private final CourseOfferingRepository offerings;
    private final EnrollmentRepository enrollments;
    private final AccessGrantRepository grants;
    private final EnrollmentCore core;
    private final CourseQueries courses;
    private final OrgAuthorizationService orgAuthorization;
    private final OrganizationQueries organizations;
    private final UserService users;

    public OfferingService(CourseOfferingRepository offerings, EnrollmentRepository enrollments, AccessGrantRepository grants,
                           EnrollmentCore core, CourseQueries courses, OrgAuthorizationService orgAuthorization,
                           OrganizationQueries organizations, UserService users) {
        this.offerings = offerings;
        this.enrollments = enrollments;
        this.grants = grants;
        this.core = core;
        this.courses = courses;
        this.orgAuthorization = orgAuthorization;
        this.organizations = organizations;
        this.users = users;
    }

    // --- B2B -------------------------------------------------------------------------------------

    @Transactional
    public OfferingResponse createB2B(CurrentActor actor, UUID organizationId, CreateB2BOfferingRequest r) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        CourseFacts course = courses.course(r.courseId()).orElseThrow(OfferingService::courseNotFound);
        if (course.ownerOrganizationId() == null) {
            if (!actor.isPlatformAdmin()) {
                throw new ApiException.Forbidden("platform_course_needs_licence",
                    "Platform courses are offered to an organization by a platform admin");
            }
        } else if (!course.ownerOrganizationId().equals(organizationId)) {
            throw courseNotFound();
        }
        if (course.status() == Course.Status.RETIRED) {
            throw new ApiException.Conflict("course_retired", "Retired courses can't be offered");
        }
        if (r.cohortId() != null) {
            if (!organizations.cohortBelongsTo(r.cohortId(), organizationId)) {
                throw new ApiException.NotFound("cohort_not_found", "Cohort not found");
            }
            if (offerings.existsByCourseIdAndCohortIdAndStatusNot(r.courseId(), r.cohortId(), CourseOffering.Status.ARCHIVED)) {
                throw new ApiException.Conflict("already_offered", "This course is already offered to this cohort");
            }
        }
        CourseOffering offering = window(() -> CourseOffering.b2b(r.courseId(), organizationId, r.cohortId(), r.title(),
            r.startsAt(), r.endsAt(), actor.userId()));
        if (r.pinnedVersionId() != null) {
            requirePublishedVersionOf(r.pinnedVersionId(), r.courseId());
            offering.pinTo(r.pinnedVersionId());
        }
        return response(offerings.save(offering));
    }

    @Transactional(readOnly = true)
    public List<OfferingResponse> listForOrganization(CurrentActor actor, UUID organizationId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        return offerings.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public OfferingResponse getForOrganization(CurrentActor actor, UUID organizationId, UUID offeringId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        return response(orgOffering(organizationId, offeringId));
    }

    /** Moving the window moves every live cohort grant of the offering with it. */
    @Transactional
    public OfferingResponse reschedule(CurrentActor actor, UUID organizationId, UUID offeringId, UpdateOfferingRequest r) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        CourseOffering offering = orgOffering(organizationId, offeringId);
        Instant start = r.startsAt() != null ? r.startsAt() : offering.getStartsAt();
        Instant end = r.endsAt() != null ? r.endsAt() : offering.getEndsAt();
        window(() -> {
            offering.reschedule(start, end);
            return offering;
        });
        for (AccessGrant grant : grants.findLiveCohortGrants(offeringId)) {
            Instant from = start != null ? start : grant.getValidFrom();
            if (end != null && !end.isAfter(from)) {
                core.revoke(grant, AccessGrant.RevokedReason.CONTRACT_ENDED, actor.userId());
            } else {
                grant.reschedule(from, end);
            }
        }
        return response(offering);
    }

    @Transactional
    public OfferingResponse openForOrganization(CurrentActor actor, UUID organizationId, UUID offeringId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        return open(orgOffering(organizationId, offeringId));
    }

    @Transactional
    public OfferingResponse closeForOrganization(CurrentActor actor, UUID organizationId, UUID offeringId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        CourseOffering offering = orgOffering(organizationId, offeringId);
        offering.close();
        return response(offering);
    }

    @Transactional(readOnly = true)
    public List<OfferingEnrollmentRow> enrollmentsForOrganization(CurrentActor actor, UUID organizationId, UUID offeringId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        return enrollmentRows(orgOffering(organizationId, offeringId));
    }

    /** Takes away an organization learner's access to one offering (all live grants); progress is kept. */
    @Transactional
    public void revokeForOrganization(CurrentActor actor, UUID organizationId, UUID enrollmentId) {
        orgAuthorization.require(actor, organizationId, OrgPermission.MANAGE_COURSES);
        Enrollment enrollment = enrollments.findById(enrollmentId)
            .filter(e -> organizationId.equals(e.getOrganizationId()))
            .orElseThrow(() -> new ApiException.NotFound("enrollment_not_found", "Enrollment not found"));
        Instant now = Instant.now();
        grants.findByEnrollmentIdOrderByCreatedAt(enrollment.getId()).stream()
            .filter(g -> g.isLive(now))
            .forEach(g -> core.revoke(g, AccessGrant.RevokedReason.ADMIN, actor.userId()));
    }

    // --- B2C -------------------------------------------------------------------------------------

    @Transactional
    public OfferingResponse createB2C(CurrentActor actor, CreateB2COfferingRequest r) {
        requirePlatformAdmin(actor);
        CourseFacts course = courses.course(r.courseId()).orElseThrow(OfferingService::courseNotFound);
        if (course.ownerOrganizationId() != null || course.visibility() == Course.Visibility.ORG_PRIVATE) {
            throw new ApiException.Unprocessable("not_public", "Only public platform courses can be offered to individuals");
        }
        if ((r.listPriceMinor() == null) != (r.currency() == null)) {
            throw new ApiException.BadRequest("price_needs_currency", "listPriceMinor and currency go together");
        }
        return response(offerings.save(CourseOffering.b2c(r.courseId(), r.title(), r.accessDurationDays(),
            r.listPriceMinor(), r.currency(), actor.userId())));
    }

    @Transactional(readOnly = true)
    public List<OfferingResponse> listB2C(CurrentActor actor) {
        requireContractTeamOrAdmin(actor);
        return offerings.findByChannelOrderByCreatedAtDesc(CourseOffering.Channel.B2C).stream().map(this::response).toList();
    }

    @Transactional
    public OfferingResponse openB2C(CurrentActor actor, UUID offeringId) {
        requirePlatformAdmin(actor);
        return open(b2cOffering(offeringId));
    }

    @Transactional
    public OfferingResponse closeB2C(CurrentActor actor, UUID offeringId) {
        requirePlatformAdmin(actor);
        CourseOffering offering = b2cOffering(offeringId);
        offering.close();
        return response(offering);
    }

    @Transactional(readOnly = true)
    public List<OfferingEnrollmentRow> enrollmentsB2C(CurrentActor actor, UUID offeringId) {
        requireContractTeamOrAdmin(actor);
        return enrollmentRows(b2cOffering(offeringId));
    }

    // --- shared ----------------------------------------------------------------------------------

    /** Opening needs a version to enroll into; for a cohort offering it enrolls everyone currently placed. */
    private OfferingResponse open(CourseOffering offering) {
        if (offering.isOpen()) {
            return response(offering);
        }
        core.versionFor(offering);
        offering.open();
        if (offering.getCohortId() != null) {
            Instant now = Instant.now();
            for (var placement : organizations.openPlacements(offering.getCohortId())) {
                Enrollment enrollment = core.enroll(placement.userId(), offering);
                core.grant(enrollment, AccessGrant.Source.COHORT_ASSIGNMENT, placement.placementId(), null, null,
                    offering.accessStart(now), offering.accessEnd(now), null);
            }
        }
        return response(offering);
    }

    private List<OfferingEnrollmentRow> enrollmentRows(CourseOffering offering) {
        List<Enrollment> found = enrollments.findByCourseOfferingIdOrderByEnrolledAt(offering.getId());
        Map<UUID, User> people = users.findByIds(found.stream().map(Enrollment::getUserId).toList());
        Map<UUID, List<AccessGrant>> grantsByEnrollment = found.isEmpty() ? Map.of()
            : grants.findUnrevoked(found.stream().map(Enrollment::getId).toList()).stream()
                .collect(Collectors.groupingBy(AccessGrant::getEnrollmentId));
        Instant now = Instant.now();
        return found.stream().map(e -> {
            User u = people.get(e.getUserId());
            List<AccessGrant> live = grantsByEnrollment.getOrDefault(e.getId(), List.of());
            return new OfferingEnrollmentRow(e.getId(), e.getUserId(), u.getEmail(), u.getFirstName() + " " + u.getLastName(),
                e.getStatus(), e.getEnrolledAt(), live.stream().anyMatch(g -> g.isActiveAt(now)), accessUntil(live, now));
        }).toList();
    }

    /** Latest end among live grants; null when any live grant is open-ended or there is none. */
    static Instant accessUntil(List<AccessGrant> grants, Instant now) {
        List<AccessGrant> live = grants.stream().filter(g -> g.isLive(now)).toList();
        if (live.isEmpty() || live.stream().anyMatch(g -> g.getValidUntil() == null)) {
            return null;
        }
        return live.stream().map(AccessGrant::getValidUntil).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private void requirePublishedVersionOf(UUID versionId, UUID courseId) {
        courses.version(versionId)
            .filter(v -> v.courseId().equals(courseId) && v.status() == CourseVersion.Status.PUBLISHED)
            .orElseThrow(() -> new ApiException.Unprocessable("version_not_published",
                "Pinned version must be a published version of this course"));
    }

    private CourseOffering orgOffering(UUID organizationId, UUID offeringId) {
        return offerings.findByIdAndOrganizationId(offeringId, organizationId).orElseThrow(OfferingService::offeringNotFound);
    }

    private CourseOffering b2cOffering(UUID offeringId) {
        return offerings.findById(offeringId).filter(o -> o.getChannel() == CourseOffering.Channel.B2C)
            .orElseThrow(OfferingService::offeringNotFound);
    }

    private OfferingResponse response(CourseOffering offering) {
        return OfferingResponse.of(offering, courses.course(offering.getCourseId()).map(CourseFacts::title).orElse(null));
    }

    private static CourseOffering window(java.util.function.Supplier<CourseOffering> change) {
        try {
            return change.get();
        } catch (IllegalArgumentException ex) {
            throw new ApiException.BadRequest("invalid_window", ex.getMessage());
        }
    }

    static void requirePlatformAdmin(CurrentActor actor) {
        if (!actor.isPlatformAdmin()) {
            throw new ApiException.Forbidden("forbidden", "Platform admins only");
        }
    }

    static void requireContractTeamOrAdmin(CurrentActor actor) {
        if (!actor.isPlatformAdmin() && !actor.isEnrollmentManager()) {
            throw new ApiException.Forbidden("forbidden", "Contract team or platform admins only");
        }
    }

    static ApiException offeringNotFound() {
        return new ApiException.NotFound("offering_not_found", "Offering not found");
    }

    private static ApiException courseNotFound() {
        return new ApiException.NotFound("course_not_found", "Course not found");
    }
}
