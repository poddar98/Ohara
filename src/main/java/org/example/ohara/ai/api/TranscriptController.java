package org.example.ohara.ai.api;

import jakarta.validation.Valid;
import org.example.ohara.ai.api.dto.AiStatusResponse;
import org.example.ohara.ai.api.dto.ReviewRequest;
import org.example.ohara.ai.api.dto.TranscriptResponse;
import org.example.ohara.ai.api.dto.TranscriptSearchHit;
import org.example.ohara.ai.application.TranscriptService;
import org.example.ohara.common.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class TranscriptController {

    private final TranscriptService transcripts;

    public TranscriptController(TranscriptService transcripts) {
        this.transcripts = transcripts;
    }

    // --- learners ---

    @GetMapping("/api/lessons/{lessonId}/contents/{contentId}/transcript")
    public TranscriptResponse lessonTranscript(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID lessonId,
                                               @PathVariable UUID contentId) {
        return transcripts.lessonTranscript(actor, lessonId, contentId);
    }

    @GetMapping("/api/enrollments/{enrollmentId}/transcripts/search")
    public List<TranscriptSearchHit> search(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId,
                                            @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return transcripts.search(actor, enrollmentId, q, limit);
    }

    // --- staff ---

    @GetMapping("/api/assets/{assetId}/ai")
    public AiStatusResponse status(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId) {
        return transcripts.status(actor, assetId);
    }

    @GetMapping("/api/assets/{assetId}/transcript")
    public TranscriptResponse transcript(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId,
                                         @RequestParam(required = false) Integer version) {
        return transcripts.staffTranscript(actor, assetId, version);
    }

    @PostMapping("/api/assets/{assetId}/ai/transcribe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiStatusResponse transcribe(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId,
                                       @RequestParam(defaultValue = "false") boolean force) {
        return transcripts.requestTranscription(actor, assetId, force);
    }

    @PostMapping("/api/assets/{assetId}/artifacts/{artifactId}/approve")
    public AiStatusResponse approve(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId,
                                    @PathVariable UUID artifactId, @Valid @RequestBody(required = false) ReviewRequest request) {
        return transcripts.approve(actor, assetId, artifactId, request == null ? null : request.note());
    }

    @PostMapping("/api/assets/{assetId}/artifacts/{artifactId}/reject")
    public AiStatusResponse reject(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId,
                                   @PathVariable UUID artifactId, @Valid @RequestBody(required = false) ReviewRequest request) {
        return transcripts.reject(actor, assetId, artifactId, request == null ? null : request.note());
    }
}
