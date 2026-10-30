package org.example.ohara.course.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.course.api.dto.CourseResponse;
import org.example.ohara.course.api.dto.CreateCourseRequest;
import org.example.ohara.course.api.dto.InstructorRequest;
import org.example.ohara.course.api.dto.InstructorResponse;
import org.example.ohara.course.api.dto.UpdateCourseRequest;
import org.example.ohara.course.application.CourseService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CourseController {

    private final CourseService courses;

    public CourseController(CourseService courses) {
        this.courses = courses;
    }

    @PostMapping("/api/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(@AuthenticationPrincipal CurrentActor actor, @Valid @RequestBody CreateCourseRequest request) {
        return courses.create(actor, request);
    }

    /** Courses the caller teaches. */
    @GetMapping("/api/courses")
    public PageResponse<CourseResponse> mine(@AuthenticationPrincipal CurrentActor actor,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return courses.taughtBy(actor, page, size);
    }

    @GetMapping("/api/orgs/{orgId}/courses")
    public PageResponse<CourseResponse> ofOrganization(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return courses.ownedBy(actor, orgId, page, size);
    }

    @GetMapping("/api/admin/courses")
    public PageResponse<CourseResponse> all(@AuthenticationPrincipal CurrentActor actor,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return courses.all(actor, page, size);
    }

    @GetMapping("/api/courses/{courseId}")
    public CourseResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId) {
        return courses.get(actor, courseId);
    }

    @PatchMapping("/api/courses/{courseId}")
    public CourseResponse update(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                 @Valid @RequestBody UpdateCourseRequest request) {
        return courses.update(actor, courseId, request);
    }

    @PostMapping("/api/courses/{courseId}/retire")
    public CourseResponse retire(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId) {
        return courses.retire(actor, courseId);
    }

    @GetMapping("/api/courses/{courseId}/instructors")
    public List<InstructorResponse> instructors(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId) {
        return courses.instructors(actor, courseId);
    }

    /** Adds the user to the course staff or changes their role. */
    @PutMapping("/api/courses/{courseId}/instructors")
    public InstructorResponse putInstructor(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                            @Valid @RequestBody InstructorRequest request) {
        return courses.putInstructor(actor, courseId, request.userId(), request.role());
    }

    @DeleteMapping("/api/courses/{courseId}/instructors/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstructor(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                 @PathVariable UUID userId) {
        courses.removeInstructor(actor, courseId, userId);
    }
}
