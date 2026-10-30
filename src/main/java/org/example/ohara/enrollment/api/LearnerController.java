package org.example.ohara.enrollment.api;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.enrollment.api.dto.LearnerCurriculum;
import org.example.ohara.enrollment.api.dto.LessonView;
import org.example.ohara.enrollment.api.dto.MyCourseResponse;
import org.example.ohara.enrollment.application.LearnerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class LearnerController {

    private final LearnerService learner;

    public LearnerController(LearnerService learner) {
        this.learner = learner;
    }

    @GetMapping("/api/me/courses")
    public List<MyCourseResponse> myCourses(@AuthenticationPrincipal CurrentActor actor) {
        return learner.myCourses(actor);
    }

    @GetMapping("/api/enrollments/{enrollmentId}/curriculum")
    public LearnerCurriculum curriculum(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId) {
        return learner.curriculum(actor, enrollmentId);
    }

    @GetMapping("/api/lessons/{lessonId}")
    public LessonView lesson(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID lessonId) {
        return learner.lesson(actor, lessonId);
    }
}
