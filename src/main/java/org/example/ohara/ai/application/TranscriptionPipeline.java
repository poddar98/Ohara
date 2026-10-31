package org.example.ohara.ai.application;

import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.example.ohara.content.application.AssetQueries;
import org.example.ohara.content.application.AssetQueries.MediaSource;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transcription (docs 07 §3): submit → poll → store segments → transcript and caption artifacts. Runs after a video
 * is READY and never affects its playability. A source already transcribed (same {@code sourceRef}) is not sent to the
 * provider again unless staff force it, so repeated triggers cost nothing. Provider failures are retried up to
 * {@code maxAttempts}; then the run is FAILED (visible to staff, who can request it again).
 * <p>
 * Confident transcripts and their captions are published straight away (docs 07: captions auto-publish); empty or
 * low-confidence ones wait as GENERATED for staff review.
 */
@Component
class TranscriptionPipeline {

    static final String TRANSCRIBE_JOB = "AI_TRANSCRIBE";
    static final String CHECK_JOB = "AI_TRANSCRIBE_CHECK";

    record TranscribeWork(UUID assetId, UUID requestedBy, boolean force) {
    }

    record CheckWork(UUID transcriptionId, int poll) {
    }

    private static final Logger log = LoggerFactory.getLogger(TranscriptionPipeline.class);

    private final AssetQueries assets;
    private final AiStore store;
    private final SpeechToText stt;
    private final Captions captions;
    private final JobScheduler jobs;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final List<String> languages;
    private final long microUsdPerMinute;
    private final double minConfidence;
    private final Duration pollInterval;
    private final int maxPolls;
    private final int maxAttempts;

    TranscriptionPipeline(AssetQueries assets, AiStore store, SpeechToText stt, Captions captions, JobScheduler jobs,
                          TransactionTemplate tx, JsonMapper json,
                          @Value("${ohara.ai.stt.languages:en-IN,hi-IN}") List<String> languages,
                          @Value("${ohara.ai.pricing.stt-micro-usd-per-minute:24000}") long microUsdPerMinute,
                          @Value("${ohara.ai.stt.min-confidence:0.6}") double minConfidence,
                          @Value("${ohara.ai.poll-interval:30s}") Duration pollInterval,
                          @Value("${ohara.ai.max-polls:480}") int maxPolls,
                          @Value("${ohara.ai.max-attempts:3}") int maxAttempts) {
        this.assets = assets;
        this.store = store;
        this.stt = stt;
        this.captions = captions;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.languages = languages;
        this.microUsdPerMinute = microUsdPerMinute;
        this.minConfidence = minConfidence;
        this.pollInterval = pollInterval;
        this.maxPolls = maxPolls;
        this.maxAttempts = maxAttempts;
    }

    /** Queues a transcription; joins the caller's transaction. */
    void request(UUID assetId, UUID requestedBy, boolean force) {
        jobs.enqueue(JobRequest.of(TRANSCRIBE_JOB, TRANSCRIBE_JOB + ":" + assetId + ":" + UUID.randomUUID(),
            new TranscribeWork(assetId, requestedBy, force)).withMaxAttempts(5));
    }

    /** AI_TRANSCRIBE. */
    void transcribe(TranscribeWork work) {
        MediaSource source = assets.mediaSource(work.assetId()).orElse(null);
        if (source == null || !source.ready()) {
            return;
        }
        if (!work.force() && store.hasCompleted(source.assetId(), source.sourceRef())) {
            tx.executeWithoutResult(s -> captions.ensureCurrent(source));
            return;
        }
        UUID id;
        var inFlight = store.inFlight(source.assetId());
        if (inFlight.isPresent()) {
            if (inFlight.get().providerJobRef() != null) {
                return; // already submitted; its checks are running
            }
            id = inFlight.get().id(); // a previous attempt crashed before submitting
        } else {
            id = tx.execute(s -> store.insertTranscription(source.assetId(), source.sourceRef(), stt.provider(), stt.model(),
                work.requestedBy()).orElse(null));
            if (id == null) {
                return;
            }
        }
        submit(id, source);
    }

    /** AI_TRANSCRIBE_CHECK. */
    void check(CheckWork work) {
        AiStore.TranscriptionRow run = store.transcription(work.transcriptionId()).orElse(null);
        if (run == null || !run.status().equals("SUBMITTED") || run.providerJobRef() == null) {
            return;
        }
        SpeechToText.Result result = stt.status(run.providerJobRef());
        switch (result.state()) {
            case PENDING -> {
                if (work.poll() >= maxPolls) {
                    retryOrFail(run, "stt_timeout", "Transcription didn't finish in time");
                } else {
                    scheduleCheck(run.id(), work.poll() + 1);
                }
            }
            case FAILED -> retryOrFail(run, "stt_failed", result.failure());
            case COMPLETE -> complete(run, result);
        }
    }

    private void complete(AiStore.TranscriptionRow run, SpeechToText.Result result) {
        MediaSource source = assets.mediaSource(run.assetId()).orElse(null);
        tx.executeWithoutResult(s -> {
            int version = store.nextTranscriptVersion(run.assetId());
            store.insertSegments(run.assetId(), version, result.segments());
            long durationMs = source != null ? source.durationMs()
                : result.segments().stream().mapToLong(SpeechToText.Segment::endMs).max().orElse(0);
            int audioSeconds = (int) Math.ceil(durationMs / 1000.0);
            long cost = (long) Math.ceil(audioSeconds * microUsdPerMinute / 60.0);
            String language = result.language() != null ? result.language() : languages.getFirst();
            store.markCompleted(run.id(), language, audioSeconds, cost);

            double confidence = result.segments().stream().filter(x -> x.confidence() != null)
                .mapToDouble(SpeechToText.Segment::confidence).average().orElse(0);
            boolean publish = !result.segments().isEmpty() && confidence >= minConfidence;
            String status = publish ? "APPROVED" : "GENERATED";
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("language", language);
            content.put("segmentCount", result.segments().size());
            content.put("averageConfidence", Math.round(confidence * 1000) / 1000.0);
            content.put("needsReview", !publish);
            if (publish) {
                store.supersedeApproved(run.assetId(), "TRANSCRIPT");
            }
            store.insertArtifact(run.assetId(), "TRANSCRIPT", run.sourceRef(), version, status, json.writeValueAsString(content),
                null, run.provider(), run.model(), audioSeconds, cost, run.id());

            if (!result.segments().isEmpty() && source != null && source.deliveryPrefix() != null) {
                String key = captions.write(source, version, language, store.segments(run.assetId(), version));
                if (publish) {
                    store.supersedeApproved(run.assetId(), "CAPTIONS");
                }
                store.insertArtifact(run.assetId(), "CAPTIONS", run.sourceRef(), version, status,
                    json.writeValueAsString(Map.of("language", language, "format", "WEBVTT")), key, run.provider(), run.model(),
                    null, null, run.id());
            }
        });
        log.info("Asset {} transcribed ({} segments)", run.assetId(), result.segments().size());
    }

    private void retryOrFail(AiStore.TranscriptionRow run, String code, String detail) {
        MediaSource source = assets.mediaSource(run.assetId()).orElse(null);
        if (run.attempts() < maxAttempts && source != null && source.ready()) {
            log.warn("Transcription {} attempt {} failed ({}): {}; retrying", run.id(), run.attempts(), code, detail);
            submit(run.id(), source);
        } else {
            log.error("Transcription {} failed after {} attempts ({}): {}", run.id(), run.attempts(), code, detail);
            store.markFailed(run.id(), code, detail);
        }
    }

    private void submit(UUID transcriptionId, MediaSource source) {
        String ref = stt.submit(new SpeechToText.Request(source.sourceKey(), source.durationMs(), languages));
        tx.executeWithoutResult(s -> {
            store.markSubmitted(transcriptionId, ref);
            scheduleCheck(transcriptionId, 1);
        });
    }

    private void scheduleCheck(UUID transcriptionId, int poll) {
        jobs.enqueue(JobRequest.of(CHECK_JOB, CHECK_JOB + ":" + transcriptionId + ":" + UUID.randomUUID(),
                new CheckWork(transcriptionId, poll))
            .runAfter(pollInterval.isZero() ? null : Instant.now().plus(pollInterval)));
    }
}
