package org.example.ohara.enrollment.api;

import jakarta.validation.Valid;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.enrollment.api.dto.ContinueItem;
import org.example.ohara.enrollment.api.dto.HeartbeatRequest;
import org.example.ohara.enrollment.api.dto.MigrationResponse;
import org.example.ohara.enrollment.api.dto.ProgressSummary;
import org.example.ohara.enrollment.application.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    @PutMapping("/api/enrollments/{enrollmentId}/lessons/{lessonKey}/progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId,
                          @PathVariable UUID lessonKey, @Valid @RequestBody HeartbeatRequest request) {
        progress.heartbeat(actor, enrollmentId, lessonKey, request);
    }

    @PostMapping("/api/enrollments/{enrollmentId}/lessons/{lessonKey}/complete")
    public ProgressSummary complete(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId,
                                    @PathVariable UUID lessonKey) {
        return progress.complete(actor, enrollmentId, lessonKey);
    }

    @GetMapping("/api/enrollments/{enrollmentId}/progress")
    public ProgressSummary summary(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId) {
        return progress.summary(actor, enrollmentId);
    }

    @GetMapping("/api/me/continue-watching")
    public List<ContinueItem> continueWatching(@AuthenticationPrincipal CurrentActor actor,
                                               @RequestParam(defaultValue = "10") int limit) {
        return progress.continueWatching(actor, limit);
    }

    @PostMapping("/api/enrollments/{enrollmentId}/migrate-version")
    public MigrationResponse migrate(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId) {
        return progress.migrateToLatest(actor, enrollmentId);
    }
}
