package org.example.ohara.content.application;

import org.example.ohara.content.domain.ContentAsset;
import org.example.ohara.content.domain.MediaRendition;
import org.example.ohara.content.infrastructure.persistence.ContentAssetRepository;
import org.example.ohara.content.infrastructure.persistence.MediaRenditionRepository;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The video pipeline: probe → choose ladder → submit transcode → poll → verify outputs → READY.
 * Each step is a durable job, so a crash anywhere resumes from the asset's recorded state; every transition checks the
 * asset's status and generation first, so duplicate or stale jobs are no-ops. Failed or unverifiable transcodes are
 * retried up to {@code maxAttempts}; then the asset is FAILED with its source kept for a manual reprocess.
 */
@Component
class MediaPipeline {

    static final String CHECK_JOB = "MEDIA_CHECK";

    public record CheckWork(UUID assetId, int generation, int poll) {
    }

    private static final Logger log = LoggerFactory.getLogger(MediaPipeline.class);

    private final ContentAssetRepository assets;
    private final MediaRenditionRepository renditions;
    private final Transcoder transcoder;
    private final HlsVerifier verifier;
    private final RecordingAdopter adopter;
    private final VideoEvents events;
    private final JobScheduler jobs;
    private final TransactionTemplate tx;
    private final Duration pollInterval;
    private final int maxPolls;
    private final int maxAttempts;

    MediaPipeline(ContentAssetRepository assets, MediaRenditionRepository renditions, Transcoder transcoder,
                  HlsVerifier verifier, RecordingAdopter adopter, VideoEvents events, JobScheduler jobs, TransactionTemplate tx,
                  @Value("${ohara.media.poll-interval:30s}") Duration pollInterval,
                  @Value("${ohara.media.max-polls:240}") int maxPolls,
                  @Value("${ohara.media.max-transcode-attempts:3}") int maxAttempts) {
        this.assets = assets;
        this.renditions = renditions;
        this.transcoder = transcoder;
        this.verifier = verifier;
        this.adopter = adopter;
        this.events = events;
        this.jobs = jobs;
        this.tx = tx;
        this.pollInterval = pollInterval;
        this.maxPolls = maxPolls;
        this.maxAttempts = maxAttempts;
    }

    /** MEDIA_PROCESS: only acts on an UPLOADED video. */
    void process(UUID assetId) {
        ContentAsset asset = assets.findById(assetId).orElse(null);
        if (asset == null || asset.getType() != ContentAsset.Type.VIDEO || asset.getStatus() != ContentAsset.Status.UPLOADED) {
            return;
        }
        if (asset.getOrigin() == ContentAsset.Origin.LIVE_RECORDING) {
            adopt(asset);
            return;
        }
        Transcoder.MediaInfo info = transcoder.probe(asset.getSourceObjectKey());
        if (!info.valid()) {
            tx.executeWithoutResult(s -> assets.findById(assetId).ifPresent(a -> {
                a.rejected("unsupported_media", info.reason());
                events.failed(assetId, "unsupported_media");
            }));
            return;
        }
        int generation = tx.execute(s -> assets.findById(assetId).orElseThrow()
            .startProcessing(info.durationMs(), info.width(), info.height(), deliveryBase(asset)));
        submit(assetId, generation);
    }

    /** Live recordings: copy the provider's renditions into place instead of transcoding, then verify as usual. */
    private void adopt(ContentAsset asset) {
        RecordingAdopter.Recording recording;
        try {
            recording = adopter.inspect(asset.getSourceObjectKey());
        } catch (RecordingAdopter.UnusableRecordingException ex) {
            fail(asset.getId(), "recording_unusable", ex.getMessage());
            return;
        }
        int generation = tx.execute(s -> assets.findById(asset.getId()).orElseThrow()
            .startProcessing(recording.durationMs(), recording.width(), recording.height(), deliveryBase(asset)));
        ContentAsset processing = assets.findById(asset.getId()).orElseThrow();
        adopter.adopt(recording, processing.getDeliveryPrefix());
        finish(processing, generation, recording.renditions().size(), false);
    }

    /** MEDIA_CHECK: polls the transcode; verifies and finishes, schedules another poll, or retries. */
    void check(CheckWork work) {
        ContentAsset asset = assets.findById(work.assetId()).orElse(null);
        if (asset == null || asset.getStatus() != ContentAsset.Status.PROCESSING
            || asset.getProcessingGeneration() != work.generation() || asset.getTranscodeJobRef() == null) {
            return;
        }
        Transcoder.JobStatus status = transcoder.status(asset.getTranscodeJobRef());
        switch (status.state()) {
            case PENDING -> {
                if (work.poll() >= maxPolls) {
                    retryOrFail(asset, "transcode_timeout", "The transcode didn't finish in time");
                } else {
                    scheduleCheck(asset.getId(), work.generation(), work.poll() + 1);
                }
            }
            case FAILED -> retryOrFail(asset, "transcode_failed", status.failure());
            case COMPLETE -> finish(asset, work.generation(), ladderFor(asset).size(), true);
        }
    }

    private void finish(ContentAsset asset, int generation, int expectedVariants, boolean retryable) {
        List<HlsVerifier.Variant> verified;
        try {
            verified = verifier.verify(asset.getDeliveryPrefix(), asset.getDurationMs(), expectedVariants);
        } catch (HlsVerifier.VerificationException ex) {
            if (retryable) {
                retryOrFail(asset, "output_invalid", ex.getMessage());
            } else {
                fail(asset.getId(), "output_invalid", ex.getMessage());
            }
            return;
        }
        tx.executeWithoutResult(s -> {
            ContentAsset current = assets.findById(asset.getId()).orElseThrow();
            if (current.getStatus() != ContentAsset.Status.PROCESSING || current.getProcessingGeneration() != generation) {
                return;
            }
            for (HlsVerifier.Variant v : verified) {
                renditions.save(new MediaRendition(current.getId(), generation, v.height(), v.width(), v.bandwidthKbps(),
                    v.playlistKey(), v.segmentCount()));
            }
            current.processed(current.getDeliveryPrefix() + "master.m3u8");
            events.ready(current.getId());
        });
        log.info("Asset {} READY (generation {}, {} variants)", asset.getId(), generation, verified.size());
    }

    private void retryOrFail(ContentAsset asset, String code, String detail) {
        if (asset.getTranscodeAttempts() < maxAttempts) {
            log.warn("Asset {} transcode attempt {} failed ({}): {}; retrying", asset.getId(), asset.getTranscodeAttempts(), code, detail);
            submit(asset.getId(), asset.getProcessingGeneration());
        } else {
            log.error("Asset {} failed after {} transcode attempts ({}): {}", asset.getId(), asset.getTranscodeAttempts(), code, detail);
            fail(asset.getId(), code, detail);
        }
    }

    private void fail(UUID assetId, String code, String detail) {
        tx.executeWithoutResult(s -> assets.findById(assetId).ifPresent(a -> {
            a.failed(code, detail);
            events.failed(assetId, code);
        }));
    }

    private void submit(UUID assetId, int generation) {
        ContentAsset asset = assets.findById(assetId).orElseThrow();
        String ref = transcoder.submit(new Transcoder.Job(asset.getSourceObjectKey(), asset.getDeliveryPrefix(),
            ladderFor(asset), asset.getDurationMs()));
        tx.executeWithoutResult(s -> {
            assets.findById(assetId).orElseThrow().transcodeSubmitted(ref);
            scheduleCheck(assetId, generation, 1);
        });
    }

    private void scheduleCheck(UUID assetId, int generation, int poll) {
        jobs.enqueue(JobRequest.of(CHECK_JOB, CHECK_JOB + ":" + assetId + ":g" + generation + ":" + UUID.randomUUID(),
                new CheckWork(assetId, generation, poll))
            .runAfter(pollInterval.isZero() ? null : Instant.now().plus(pollInterval)));
    }

    private static List<Ladder.Rung> ladderFor(ContentAsset asset) {
        return Ladder.forSource(asset.getWidth(), asset.getHeight(), asset.getContentProfile());
    }

    private static String deliveryBase(ContentAsset asset) {
        return "delivery/org/" + (asset.getOwnerOrganizationId() == null ? "platform" : asset.getOwnerOrganizationId())
            + "/asset/" + asset.getId();
    }
}
