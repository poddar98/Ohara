package org.example.ohara.ai.application;

import org.example.ohara.ai.api.dto.AiStatusResponse;
import org.example.ohara.ai.api.dto.StudyResponse;
import org.example.ohara.ai.api.dto.TranscriptResponse;
import org.example.ohara.ai.api.dto.TranscriptSearchHit;
import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.content.application.AssetQueries;
import org.example.ohara.enrollment.api.dto.LessonView;
import org.example.ohara.enrollment.application.LearnerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transcripts for learners (approved only, behind the same lesson access check as the video) and for staff (status,
 * review, re-runs). Search only ever covers videos the learner may open in that enrollment.
 */
@Service
public class TranscriptService {

    private static final List<String> REVIEWABLE = List.of("GENERATED", "IN_REVIEW");

    private final AiStore store;
    private final AssetQueries assets;
    private final LearnerService learner;
    private final TranscriptionPipeline pipeline;
    private final GenerationPipeline generations;
    private final JsonMapper json;
    private final JsonMapper strict;

    TranscriptService(AiStore store, AssetQueries assets, LearnerService learner, TranscriptionPipeline pipeline,
                      GenerationPipeline generations, JsonMapper json) {
        this.store = store;
        this.assets = assets;
        this.learner = learner;
        this.pipeline = pipeline;
        this.generations = generations;
        this.json = json;
        this.strict = StudyMaterial.strict(json);
    }

    // --- learners ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TranscriptResponse lessonTranscript(CurrentActor actor, UUID lessonId, UUID contentId) {
        LessonView.ContentItem video = lessonVideo(actor, lessonId, contentId);
        AiStore.ArtifactRow transcript = store.approved(video.assetId(), "TRANSCRIPT")
            .orElseThrow(() -> new ApiException.NotFound("transcript_not_available", "No transcript for this video yet"));
        return transcript(transcript);
    }

    /** Approved summary, chapters and flashcards of a lesson video. */
    @Transactional(readOnly = true)
    public StudyResponse study(CurrentActor actor, UUID lessonId, UUID contentId) {
        UUID assetId = lessonVideo(actor, lessonId, contentId).assetId();
        StudyResponse.Summary summary = store.approved(assetId, "SUMMARY")
            .map(a -> json.readValue(a.content(), StudyResponse.Summary.class)).orElse(null);
        List<StudyResponse.Chapter> chapters = store.approved(assetId, "CHAPTERS")
            .map(a -> json.readValue(a.content(), ChaptersBody.class).chapters()).orElse(List.of());
        List<StudyResponse.Card> cards = store.approved(assetId, "FLASHCARDS")
            .map(a -> json.readValue(a.content(), CardsBody.class).cards()).orElse(List.of());
        return new StudyResponse(assetId, summary, chapters, cards);
    }

    private record ChaptersBody(List<StudyResponse.Chapter> chapters) {
    }

    private record CardsBody(List<StudyResponse.Card> cards) {
    }

    private LessonView.ContentItem lessonVideo(CurrentActor actor, UUID lessonId, UUID contentId) {
        LessonView lesson = learner.lesson(actor, lessonId);
        return lesson.contents().stream()
            .filter(c -> c.id().equals(contentId) && "VIDEO".equals(c.kind()))
            .findFirst()
            .orElseThrow(() -> new ApiException.NotFound("content_not_found", "No such video in this lesson"));
    }

    @Transactional(readOnly = true)
    public List<TranscriptSearchHit> search(CurrentActor actor, UUID enrollmentId, String query, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.length() < 2 || q.length() > 200) {
            throw new ApiException.BadRequest("invalid_query", "Search for 2 to 200 characters");
        }
        Map<UUID, LearnerService.OpenVideo> byAsset = learner.openVideos(actor, enrollmentId).videos().stream()
            .collect(Collectors.toMap(LearnerService.OpenVideo::assetId, Function.identity(), (first, later) -> first, LinkedHashMap::new));
        return store.search(byAsset.keySet(), q, Math.clamp(limit, 1, 50)).stream()
            .map(h -> {
                LearnerService.OpenVideo v = byAsset.get(h.assetId());
                return new TranscriptSearchHit(v.lessonId(), v.lessonTitle(), v.contentId(), h.assetId(), h.startMs(), h.endMs(), h.snippet());
            })
            .toList();
    }

    // --- staff ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AiStatusResponse status(CurrentActor actor, UUID assetId) {
        assets.requireRead(actor, assetId);
        AiStatusResponse.Transcription run = store.latestTranscription(assetId)
            .map(t -> new AiStatusResponse.Transcription(t.id(), t.status(), t.provider(), t.model(), t.attempts(), t.language(),
                t.audioSeconds(), t.costMicroUsd(), t.failureCode(), t.failureDetail(), t.createdAt(), t.completedAt()))
            .orElse(null);
        List<AiStatusResponse.Generation> runs = store.latestGenerations(assetId).stream()
            .map(g -> new AiStatusResponse.Generation(g.id(), g.task(), g.status(), g.transcriptVersion(), g.promptVersion(),
                g.provider(), g.model(), g.attempts(), g.inputTokens(), g.outputTokens(), g.costMicroUsd(), g.failureCode(),
                g.failureDetail(), g.createdAt(), g.completedAt()))
            .toList();
        List<AiStatusResponse.Artifact> artifacts = store.artifacts(assetId).stream()
            .map(a -> new AiStatusResponse.Artifact(a.id(), a.type(), a.artifactVersion(), a.status(), a.transcriptVersion(),
                a.content(), a.costMicroUsd(), a.createdAt(), a.reviewedBy(), a.reviewedAt(), a.reviewNote(), a.editedBy(), a.editedAt()))
            .toList();
        return new AiStatusResponse(assetId, run, runs, artifacts);
    }

    /** The transcript artifact of a given version (default: the newest), whatever its review status. */
    @Transactional(readOnly = true)
    public TranscriptResponse staffTranscript(CurrentActor actor, UUID assetId, Integer transcriptVersion) {
        assets.requireRead(actor, assetId);
        return store.artifacts(assetId).stream()
            .filter(a -> a.type().equals("TRANSCRIPT"))
            .filter(a -> transcriptVersion == null || transcriptVersion.equals(a.transcriptVersion()))
            .findFirst()
            .map(this::transcript)
            .orElseThrow(() -> new ApiException.NotFound("transcript_not_available", "No such transcript"));
    }

    /**
     * Queues transcription. Without {@code force}, a source that was already transcribed is refused (no repeat spend);
     * with it, a new transcript version is produced (e.g. after a provider or vocabulary improvement).
     */
    @Transactional
    public AiStatusResponse requestTranscription(CurrentActor actor, UUID assetId, boolean force) {
        assets.requireManage(actor, assetId);
        AssetQueries.MediaSource source = assets.mediaSource(assetId)
            .orElseThrow(() -> new ApiException.Unprocessable("not_a_video", "Only videos can be transcribed"));
        if (!source.ready()) {
            throw new ApiException.Conflict("media_not_ready", "The video must be READY first");
        }
        if (store.inFlight(assetId).isPresent()) {
            throw new ApiException.Conflict("transcription_in_progress", "A transcription is already running");
        }
        if (!force && store.hasCompleted(assetId, source.sourceRef())) {
            throw new ApiException.Conflict("already_transcribed", "This video was already transcribed; pass force=true for a new version");
        }
        pipeline.request(assetId, actor.userId(), force);
        return status(actor, assetId);
    }

    /** Publishes a draft (low-confidence transcript, its captions): it replaces the type's currently approved one. */
    @Transactional
    public AiStatusResponse approve(CurrentActor actor, UUID assetId, UUID artifactId, String note) {
        assets.requireManage(actor, assetId);
        AiStore.ArtifactRow artifact = artifact(assetId, artifactId);
        if (!REVIEWABLE.contains(artifact.status())) {
            throw new ApiException.Conflict("artifact_not_reviewable", "This artifact is " + artifact.status().toLowerCase());
        }
        store.supersedeApproved(assetId, artifact.type());
        store.review(artifactId, "APPROVED", actor.userId(), note);
        if (artifact.type().equals("TRANSCRIPT")) {
            generations.transcriptApproved(assetId, artifact.transcriptVersion());
        }
        return status(actor, assetId);
    }

    /**
     * Generates study material from the approved transcript: SUMMARY (with chapters), QUIZ_DRAFT or FLASHCARDS.
     * Without {@code force}, output that already exists for this transcript and prompt version is refused (no repeat spend).
     */
    @Transactional
    public AiStatusResponse requestGeneration(CurrentActor actor, UUID assetId, String type, String instructions, boolean force) {
        assets.requireManage(actor, assetId);
        StudyMaterial.Task task = type.equals("SUMMARY") ? StudyMaterial.Task.SUMMARY_CHAPTERS : StudyMaterial.Task.valueOf(type);
        AiStore.ArtifactRow transcript = store.approved(assetId, "TRANSCRIPT")
            .orElseThrow(() -> new ApiException.Conflict("transcript_required", "Approve a transcript first"));
        if (store.generationInFlight(assetId, task.name()).isPresent()) {
            throw new ApiException.Conflict("generation_in_progress", "This is already being generated");
        }
        if (!force && store.hasCompletedGeneration(assetId, task.name(), transcript.transcriptVersion(), task.promptVersion)) {
            throw new ApiException.Conflict("already_generated", "Already generated from this transcript; pass force=true to regenerate");
        }
        generations.request(assetId, task, transcript.transcriptVersion(),
            instructions == null || instructions.isBlank() ? null : instructions.strip(), actor.userId())
            .orElseThrow(() -> new ApiException.Conflict("generation_in_progress", "This is already being generated"));
        return status(actor, assetId);
    }

    /** Staff corrections to a draft before approval; checked like model output and recorded as an edit. */
    @Transactional
    public AiStatusResponse edit(CurrentActor actor, UUID assetId, UUID artifactId, Map<String, Object> content) {
        assets.requireManage(actor, assetId);
        AiStore.ArtifactRow artifact = artifact(assetId, artifactId);
        if (!REVIEWABLE.contains(artifact.status())) {
            throw new ApiException.Conflict("artifact_not_reviewable", "Only drafts can be edited");
        }
        Class<?> shape = switch (artifact.type()) {
            case "SUMMARY" -> StudyMaterial.Summary.class;
            case "CHAPTERS" -> StudyMaterial.Chapters.class;
            case "QUIZ_DRAFT" -> StudyMaterial.Quiz.class;
            case "FLASHCARDS" -> StudyMaterial.Flashcards.class;
            default -> throw new ApiException.Conflict("artifact_not_editable", "Transcripts and captions aren't edited here");
        };
        List<StudyMaterial.Span> spans = store.segments(assetId, artifact.transcriptVersion()).stream()
            .map(s -> new StudyMaterial.Span(s.startMs(), s.endMs())).toList();
        Object body;
        try {
            body = strict.readValue(json.writeValueAsString(content), shape);
            StudyMaterial.validate(body, spans);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ApiException.Unprocessable("invalid_content", "Content doesn't have the required shape: " + e.getOriginalMessage());
        } catch (StudyMaterial.Invalid e) {
            throw new ApiException.Unprocessable("invalid_content", "Content is invalid").with("problems", e.problems);
        }
        store.edit(artifactId, json.writeValueAsString(body), actor.userId());
        return status(actor, assetId);
    }

    /** Withdraws a draft or a published artifact; learners stop seeing it immediately. */
    @Transactional
    public AiStatusResponse reject(CurrentActor actor, UUID assetId, UUID artifactId, String note) {
        assets.requireManage(actor, assetId);
        AiStore.ArtifactRow artifact = artifact(assetId, artifactId);
        if (!REVIEWABLE.contains(artifact.status()) && !artifact.status().equals("APPROVED")) {
            throw new ApiException.Conflict("artifact_not_reviewable", "This artifact is " + artifact.status().toLowerCase());
        }
        store.review(artifactId, "REJECTED", actor.userId(), note);
        return status(actor, assetId);
    }

    private AiStore.ArtifactRow artifact(UUID assetId, UUID artifactId) {
        return store.artifact(assetId, artifactId)
            .orElseThrow(() -> new ApiException.NotFound("artifact_not_found", "Artifact not found"));
    }

    private TranscriptResponse transcript(AiStore.ArtifactRow artifact) {
        Object language = json.readValue(artifact.content(), Map.class).get("language");
        List<TranscriptResponse.Segment> segments = store.segments(artifact.assetId(), artifact.transcriptVersion()).stream()
            .map(s -> new TranscriptResponse.Segment(s.seq(), s.startMs(), s.endMs(), s.speaker(), s.text(), s.confidence()))
            .toList();
        return new TranscriptResponse(artifact.assetId(), artifact.transcriptVersion(), language == null ? null : language.toString(),
            artifact.status(), segments);
    }
}
