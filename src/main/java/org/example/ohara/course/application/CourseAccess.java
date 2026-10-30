package org.example.ohara.course.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.domain.Course;
import org.example.ohara.course.domain.CourseInstructor;
import org.example.ohara.course.infrastructure.persistence.CourseInstructorRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may do what with a course as staff (authoring, publishing, staff management). Learner access is a separate
 * concern (enrollment). For an organization's course, course-staff rights only count while the person is still an
 * active instructor or admin of that organization.
 */
@Component
class CourseAccess {

    /** Ordered from least to most capable. */
    enum StaffRole { READ_ONLY, TA, INSTRUCTOR, OWNER, ADMIN }

    private final CourseInstructorRepository instructors;
    private final OrgAuthorizationService orgAuthorization;

    CourseAccess(CourseInstructorRepository instructors, OrgAuthorizationService orgAuthorization) {
        this.instructors = instructors;
        this.orgAuthorization = orgAuthorization;
    }

    Optional<StaffRole> roleOf(CurrentActor actor, Course course) {
        if (actor.isPlatformAdmin()) {
            return Optional.of(StaffRole.ADMIN);
        }
        if (!course.isPlatformOwned()
            && orgAuthorization.userHas(course.getOwnerOrganizationId(), actor.userId(), OrgPermission.MANAGE_COURSES)) {
            return Optional.of(StaffRole.ADMIN);
        }
        Optional<StaffRole> fromCourse = instructors.findById(new CourseInstructor.Key(course.getId(), actor.userId()))
            .filter(ci -> course.isPlatformOwned()
                || orgAuthorization.userHas(course.getOwnerOrganizationId(), actor.userId(), OrgPermission.AUTHOR_COURSES))
            .map(ci -> switch (ci.getRole()) {
                case OWNER -> StaffRole.OWNER;
                case INSTRUCTOR -> StaffRole.INSTRUCTOR;
                case TA -> StaffRole.TA;
            });
        if (fromCourse.isPresent()) {
            return fromCourse;
        }
        return actor.isPlatformSupport() ? Optional.of(StaffRole.READ_ONLY) : Optional.empty();
    }

    /** Non-staff get 404 so course ids can't be probed; staff without enough rights get 403. */
    StaffRole require(CurrentActor actor, Course course, StaffRole minimum) {
        StaffRole role = roleOf(actor, course).orElseThrow(CourseAccess::notFound);
        if (role.compareTo(minimum) < 0) {
            throw new ApiException.Forbidden("forbidden", "You are not allowed to perform this action on this course");
        }
        return role;
    }

    /** Platform courses: platform admins. Organization courses: the org's admins and instructors. */
    void requireCanCreate(CurrentActor actor, UUID ownerOrganizationId) {
        if (ownerOrganizationId == null) {
            if (!actor.isPlatformAdmin()) {
                throw new ApiException.Forbidden("forbidden", "Only platform admins create platform courses");
            }
            return;
        }
        orgAuthorization.require(actor, ownerOrganizationId, OrgPermission.AUTHOR_COURSES);
    }

    static ApiException notFound() {
        return new ApiException.NotFound("course_not_found", "Course not found");
    }
}
