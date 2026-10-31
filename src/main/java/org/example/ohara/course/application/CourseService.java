package org.example.ohara.course.application;

import org.example.ohara.platform.audit.AuditLog;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.course.api.dto.CourseResponse;
import org.example.ohara.course.api.dto.CreateCourseRequest;
import org.example.ohara.course.api.dto.InstructorResponse;
import org.example.ohara.course.api.dto.UpdateCourseRequest;
import org.example.ohara.course.application.CourseAccess.StaffRole;
import org.example.ohara.course.domain.Course;
import org.example.ohara.course.domain.CourseInstructor;
import org.example.ohara.course.domain.CourseVersion;
import org.example.ohara.course.infrastructure.persistence.CourseInstructorRepository;
import org.example.ohara.course.infrastructure.persistence.CourseRepository;
import org.example.ohara.course.infrastructure.persistence.CourseVersionRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Courses and their staff. Curriculum editing lives in {@link CurriculumService}. */
@Service
public class CourseService {

    private final CourseRepository courses;
    private final CourseVersionRepository versions;
    private final CourseInstructorRepository instructors;
    private final CourseAccess access;
    private final OrgAuthorizationService orgAuthorization;
    private final AuditLog audit;
    private final UserService userService;

    public CourseService(CourseRepository courses, CourseVersionRepository versions, CourseInstructorRepository instructors,
                         CourseAccess access, OrgAuthorizationService orgAuthorization, UserService userService, AuditLog audit) {
        this.audit = audit;
        this.courses = courses;
        this.versions = versions;
        this.instructors = instructors;
        this.access = access;
        this.orgAuthorization = orgAuthorization;
        this.userService = userService;
    }

    /** Creates the course with an empty draft v1; the creator becomes its OWNER. */
    @Transactional
    public CourseResponse create(CurrentActor actor, CreateCourseRequest request) {
        UUID orgId = request.ownerOrganizationId();
        access.requireCanCreate(actor, orgId);
        Course.Visibility visibility = request.visibility() != null ? request.visibility()
            : orgId == null ? Course.Visibility.PUBLIC : Course.Visibility.ORG_PRIVATE;
        if (visibility == Course.Visibility.ORG_PRIVATE && orgId == null) {
            throw new ApiException.BadRequest("private_needs_org", "Only organization courses can be ORG_PRIVATE");
        }
        boolean taken = orgId == null ? courses.platformSlugTaken(request.slug())
            : courses.existsByOwnerOrganizationIdAndSlug(orgId, request.slug());
        if (taken) {
            throw new ApiException.Conflict("slug_taken", "A course with this slug already exists");
        }

        Course course = courses.save(new Course(orgId, request.slug(), request.title().trim(), visibility, actor.userId()));
        instructors.save(new CourseInstructor(course.getId(), actor.userId(), CourseInstructor.Role.OWNER));
        CourseVersion draft = versions.save(new CourseVersion(course.getId(), 1, course.getTitle(), actor.userId()));
        return CourseResponse.of(course, draft.getId());
    }

    @Transactional(readOnly = true)
    public CourseResponse get(CurrentActor actor, UUID courseId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.READ_ONLY);
        return response(course);
    }

    /** Courses the caller teaches (any course-staff role). */
    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> taughtBy(CurrentActor actor, int page, int size) {
        return PageResponse.of(courses.findTaughtBy(actor.userId(), pageRequest(page, size)), this::response);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> ownedBy(CurrentActor actor, UUID organizationId, int page, int size) {
        orgAuthorization.require(actor, organizationId, OrgPermission.AUTHOR_COURSES);
        return PageResponse.of(courses.findByOwnerOrganizationId(organizationId, pageRequest(page, size)), this::response);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> all(CurrentActor actor, int page, int size) {
        if (!actor.isPlatformAdmin() && !actor.isPlatformSupport()) {
            throw new ApiException.Forbidden("forbidden", "Platform staff only");
        }
        Page<Course> result = courses.findAll(pageRequest(page, size));
        return PageResponse.of(result, this::response);
    }

    @Transactional
    public CourseResponse update(CurrentActor actor, UUID courseId, UpdateCourseRequest request) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        if (request.title() != null) {
            course.rename(request.title().trim());
        }
        if (request.visibility() != null) {
            if (request.visibility() == Course.Visibility.ORG_PRIVATE && course.isPlatformOwned()) {
                throw new ApiException.BadRequest("private_needs_org", "Only organization courses can be ORG_PRIVATE");
            }
            course.changeVisibility(request.visibility());
        }
        return response(course);
    }

    /** Hidden from new offerings; existing learners keep their access (enrollment decides). */
    @Transactional
    public CourseResponse retire(CurrentActor actor, UUID courseId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        course.retire();
        audit.record(actor.userId(), course.getOwnerOrganizationId(), "COURSE_RETIRED", "COURSE", course.getId(), null);
        return response(course);
    }

    // --- staff -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InstructorResponse> instructors(CurrentActor actor, UUID courseId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.READ_ONLY);
        return instructors.findByCourse(courseId).stream().map(this::instructorResponse).toList();
    }

    /** Adds or changes a staff member. For an organization's course they must be its instructor or admin. */
    @Transactional
    public InstructorResponse putInstructor(CurrentActor actor, UUID courseId, UUID userId, CourseInstructor.Role role) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        User user = userService.findById(userId).filter(User::isActive)
            .orElseThrow(() -> new ApiException.Unprocessable("user_not_eligible", "No active account with this id"));
        if (!course.isPlatformOwned()
            && !orgAuthorization.userHas(course.getOwnerOrganizationId(), user.getId(), OrgPermission.AUTHOR_COURSES)) {
            throw new ApiException.Unprocessable("user_not_eligible",
                "Course staff must be an instructor or admin of the organization that owns the course");
        }
        CourseInstructor existing = instructors.findById(new CourseInstructor.Key(courseId, userId)).orElse(null);
        if (existing == null) {
            existing = instructors.save(new CourseInstructor(courseId, userId, role));
        } else {
            if (existing.getRole() == CourseInstructor.Role.OWNER && role != CourseInstructor.Role.OWNER) {
                guardLastOwner(courseId);
            }
            existing.changeRole(role);
        }
        return instructorResponse(existing);
    }

    @Transactional
    public void removeInstructor(CurrentActor actor, UUID courseId, UUID userId) {
        Course course = course(courseId);
        access.require(actor, course, StaffRole.OWNER);
        CourseInstructor existing = instructors.findById(new CourseInstructor.Key(courseId, userId))
            .orElseThrow(() -> new ApiException.NotFound("instructor_not_found", "Not on this course's staff"));
        if (existing.getRole() == CourseInstructor.Role.OWNER) {
            guardLastOwner(courseId);
        }
        instructors.delete(existing);
    }

    private void guardLastOwner(UUID courseId) {
        if (instructors.countOwners(courseId) <= 1) {
            throw new ApiException.Conflict("last_owner", "A course must keep at least one owner");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Course course(UUID courseId) {
        return courses.findById(courseId).orElseThrow(CourseAccess::notFound);
    }

    private CourseResponse response(Course course) {
        UUID draftId = versions.findByCourseIdAndStatus(course.getId(), CourseVersion.Status.DRAFT)
            .map(CourseVersion::getId).orElse(null);
        return CourseResponse.of(course, draftId);
    }

    private InstructorResponse instructorResponse(CourseInstructor ci) {
        User user = userService.findById(ci.getUserId()).orElseThrow();
        return new InstructorResponse(ci.getUserId(), user.getEmail(), user.getFirstName() + " " + user.getLastName(),
            ci.getRole(), ci.getAddedAt());
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size), Sort.by("title"));
    }
}
