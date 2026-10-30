package org.example.ohara.course.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.course.api.dto.ContentRequest;
import org.example.ohara.course.api.dto.LessonRequest;
import org.example.ohara.course.api.dto.ModuleRequest;
import org.example.ohara.course.api.dto.OrderRequest;
import org.example.ohara.course.api.dto.PublishRequest;
import org.example.ohara.course.api.dto.UpdateVersionRequest;
import org.example.ohara.course.api.dto.VersionSummary;
import org.example.ohara.course.api.dto.VersionTree;
import org.example.ohara.course.application.CurriculumService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Authoring endpoints. Mutations return the whole version tree so editors can re-render from one response. */
@RestController
@RequestMapping("/api/courses/{courseId}/versions")
public class CurriculumController {

    private final CurriculumService curriculum;

    public CurriculumController(CurriculumService curriculum) {
        this.curriculum = curriculum;
    }

    @GetMapping
    public List<VersionSummary> versions(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId) {
        return curriculum.versions(actor, courseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionTree createDraft(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId) {
        return curriculum.createDraft(actor, courseId);
    }

    @GetMapping("/{versionId}")
    public VersionTree get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId, @PathVariable UUID versionId) {
        return curriculum.tree(actor, courseId, versionId);
    }

    @PatchMapping("/{versionId}")
    public VersionTree describe(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                @PathVariable UUID versionId, @Valid @RequestBody UpdateVersionRequest request) {
        return curriculum.describe(actor, courseId, versionId, request);
    }

    @DeleteMapping("/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId, @PathVariable UUID versionId) {
        curriculum.deleteDraft(actor, courseId, versionId);
    }

    @PostMapping("/{versionId}/publish")
    public VersionTree publish(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                               @PathVariable UUID versionId, @Valid @RequestBody(required = false) PublishRequest request) {
        return curriculum.publish(actor, courseId, versionId, request == null ? null : request.changeNotes());
    }

    @PostMapping("/{versionId}/archive")
    public VersionSummary archive(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                  @PathVariable UUID versionId) {
        return curriculum.archive(actor, courseId, versionId);
    }

    @PostMapping("/{versionId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionTree addModule(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                 @PathVariable UUID versionId, @Valid @RequestBody ModuleRequest request) {
        return curriculum.addModule(actor, courseId, versionId, request.title());
    }

    @PatchMapping("/{versionId}/modules/{moduleId}")
    public VersionTree renameModule(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                    @PathVariable UUID versionId, @PathVariable UUID moduleId,
                                    @Valid @RequestBody ModuleRequest request) {
        return curriculum.renameModule(actor, courseId, versionId, moduleId, request.title());
    }

    @DeleteMapping("/{versionId}/modules/{moduleId}")
    public VersionTree removeModule(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                    @PathVariable UUID versionId, @PathVariable UUID moduleId) {
        return curriculum.removeModule(actor, courseId, versionId, moduleId);
    }

    @PutMapping("/{versionId}/modules/order")
    public VersionTree reorderModules(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                      @PathVariable UUID versionId, @Valid @RequestBody OrderRequest request) {
        return curriculum.reorderModules(actor, courseId, versionId, request.ids());
    }

    @PostMapping("/{versionId}/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionTree addLesson(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                 @PathVariable UUID versionId, @PathVariable UUID moduleId,
                                 @Valid @RequestBody LessonRequest request) {
        return curriculum.addLesson(actor, courseId, versionId, moduleId, request);
    }

    @PutMapping("/{versionId}/modules/{moduleId}/lessons/order")
    public VersionTree reorderLessons(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                      @PathVariable UUID versionId, @PathVariable UUID moduleId,
                                      @Valid @RequestBody OrderRequest request) {
        return curriculum.reorderLessons(actor, courseId, versionId, moduleId, request.ids());
    }

    @PatchMapping("/{versionId}/lessons/{lessonId}")
    public VersionTree updateLesson(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                    @PathVariable UUID versionId, @PathVariable UUID lessonId,
                                    @Valid @RequestBody LessonRequest request) {
        return curriculum.updateLesson(actor, courseId, versionId, lessonId, request);
    }

    @DeleteMapping("/{versionId}/lessons/{lessonId}")
    public VersionTree removeLesson(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                    @PathVariable UUID versionId, @PathVariable UUID lessonId) {
        return curriculum.removeLesson(actor, courseId, versionId, lessonId);
    }

    @PostMapping("/{versionId}/lessons/{lessonId}/contents")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionTree addContent(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                  @PathVariable UUID versionId, @PathVariable UUID lessonId,
                                  @Valid @RequestBody ContentRequest request) {
        return curriculum.addContent(actor, courseId, versionId, lessonId, request);
    }

    @PutMapping("/{versionId}/lessons/{lessonId}/contents/order")
    public VersionTree reorderContents(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                       @PathVariable UUID versionId, @PathVariable UUID lessonId,
                                       @Valid @RequestBody OrderRequest request) {
        return curriculum.reorderContents(actor, courseId, versionId, lessonId, request.ids());
    }

    @PatchMapping("/{versionId}/contents/{contentId}")
    public VersionTree updateContent(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                     @PathVariable UUID versionId, @PathVariable UUID contentId,
                                     @Valid @RequestBody ContentRequest request) {
        return curriculum.updateContent(actor, courseId, versionId, contentId, request);
    }

    @DeleteMapping("/{versionId}/contents/{contentId}")
    public VersionTree removeContent(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID courseId,
                                     @PathVariable UUID versionId, @PathVariable UUID contentId) {
        return curriculum.removeContent(actor, courseId, versionId, contentId);
    }
}
