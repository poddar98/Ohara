package org.example.ohara.content.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.content.api.dto.AssetResponse;
import org.example.ohara.content.api.dto.CompleteUploadRequest;
import org.example.ohara.content.api.dto.PresignedPartResponse;
import org.example.ohara.content.api.dto.StartUploadRequest;
import org.example.ohara.content.api.dto.UploadResponse;
import org.example.ohara.content.domain.ContentAsset;
import org.example.ohara.content.domain.MediaUpload;
import org.example.ohara.content.infrastructure.persistence.ContentAssetRepository;
import org.example.ohara.content.infrastructure.persistence.MediaRenditionRepository;
import org.example.ohara.content.infrastructure.persistence.MediaUploadRepository;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Asset library and direct-to-storage uploads. The API only issues presigned part URLs and records state; file bytes
 * go from the client to object storage. Videos are queued for processing on completion; documents and images are
 * checked and become READY immediately.
 */
@Service
public class AssetService {

    public static final String PROCESS_JOB = "MEDIA_PROCESS";

    static final Duration UPLOAD_TTL = Duration.ofHours(24);
    static final Duration PART_URL_TTL = Duration.ofMinutes(15);
    static final long SINGLE_PART_LIMIT = 100L * 1024 * 1024;
    static final long MIN_PART_SIZE = 16L * 1024 * 1024;
    static final int MAX_PARTS = 10_000;

    record Rule(long maxBytes, Set<String> mimeTypes) {
    }

    static final Map<ContentAsset.Type, Rule> RULES = Map.of(
        ContentAsset.Type.VIDEO, new Rule(20L * 1024 * 1024 * 1024,
            Set.of("video/mp4", "video/quicktime", "video/x-matroska", "video/webm")),
        ContentAsset.Type.DOCUMENT, new Rule(100L * 1024 * 1024, Set.of("application/pdf")),
        ContentAsset.Type.IMAGE, new Rule(10L * 1024 * 1024, Set.of("image/png", "image/jpeg", "image/webp")));

    private static final Map<String, String> EXTENSIONS = Map.of(
        "video/mp4", "mp4", "video/quicktime", "mov", "video/x-matroska", "mkv", "video/webm", "webm",
        "application/pdf", "pdf", "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private final ContentAssetRepository assets;
    private final MediaUploadRepository uploads;
    private final ObjectStorage storage;
    private final AssetAccess access;
    private final JobScheduler jobs;
    private final MediaRenditionRepository renditions;

    public AssetService(ContentAssetRepository assets, MediaUploadRepository uploads, ObjectStorage storage,
                        AssetAccess access, JobScheduler jobs, MediaRenditionRepository renditions) {
        this.assets = assets;
        this.uploads = uploads;
        this.storage = storage;
        this.access = access;
        this.jobs = jobs;
        this.renditions = renditions;
    }

    // --- uploads -----------------------------------------------------------------------------------

    /** Creates the asset and an upload session. Repeating with the same client key returns the same session. */
    @Transactional
    public UploadResponse startUpload(CurrentActor actor, StartUploadRequest r, String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? UUID.randomUUID().toString() : clientKey.trim();
        if (key.length() > 64) {
            throw new ApiException.BadRequest("invalid_client_key", "Idempotency-Key is limited to 64 characters");
        }
        var previous = uploads.findByInitiatedByAndClientKey(actor.userId(), key);
        if (previous.isPresent()) {
            return response(previous.get(), asset(previous.get().getContentAssetId()));
        }
        access.requireManageOwner(actor, r.ownerOrganizationId());
        Rule rule = RULES.get(r.type());
        String mime = r.mimeType().toLowerCase();
        if (!rule.mimeTypes().contains(mime)) {
            throw new ApiException.Unprocessable("unsupported_type", "Allowed types for " + r.type() + ": " + rule.mimeTypes());
        }
        if (r.bytes() > rule.maxBytes()) {
            throw new ApiException.Unprocessable("file_too_large", r.type() + " files are limited to " + rule.maxBytes() + " bytes");
        }

        ContentAsset asset = assets.save(new ContentAsset(r.ownerOrganizationId(), r.type(), r.title().trim(),
            r.fileName().trim(), mime, r.contentProfile(), actor.userId()));
        long partSize = partSize(r.bytes());
        int partCount = (int) Math.ceilDiv(r.bytes(), partSize);
        String objectKey = "org/" + (r.ownerOrganizationId() == null ? "platform" : r.ownerOrganizationId())
            + "/asset/" + asset.getId() + "/source/" + UUID.randomUUID() + "." + EXTENSIONS.get(mime);
        String storageUploadId = storage.startMultipart(objectKey, mime);
        MediaUpload upload = uploads.save(new MediaUpload(asset.getId(), storageUploadId, objectKey, r.bytes(), mime,
            r.sha256(), partSize, partCount, Instant.now().plus(UPLOAD_TTL), actor.userId(), key));
        return response(upload, asset);
    }

    @Transactional(readOnly = true)
    public List<PresignedPartResponse> presignParts(CurrentActor actor, UUID uploadId, List<Integer> partNumbers) {
        MediaUpload upload = ownOpenUpload(actor, uploadId);
        return partNumbers.stream().distinct().sorted().map(n -> {
            if (n < 1 || n > upload.getPartCount()) {
                throw new ApiException.BadRequest("invalid_part", "Part numbers run from 1 to " + upload.getPartCount());
            }
            long maxBytes = n < upload.getPartCount() ? upload.getPartSizeBytes()
                : upload.getDeclaredBytes() - (long) (upload.getPartCount() - 1) * upload.getPartSizeBytes();
            var part = storage.presignPart(upload.getObjectKey(), upload.getStorageUploadId(), n, maxBytes, PART_URL_TTL);
            return new PresignedPartResponse(part.partNumber(), part.url(), part.expiresAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public UploadResponse uploadStatus(CurrentActor actor, UUID uploadId) {
        MediaUpload upload = ownUpload(actor, uploadId);
        return response(upload, asset(upload.getContentAssetId()));
    }

    /**
     * Assembles the parts, checks size (and checksum when declared), then hands the asset on. Idempotent: completing
     * an already completed upload returns the asset. Content checks failing reject the asset; a new upload is needed.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AssetResponse complete(CurrentActor actor, UUID uploadId, CompleteUploadRequest r) {
        MediaUpload upload = ownUpload(actor, uploadId);
        ContentAsset asset = asset(upload.getContentAssetId());
        if (upload.getStatus() == MediaUpload.Status.COMPLETED) {
            return AssetResponse.of(asset);
        }
        if (!upload.isOpen(Instant.now())) {
            throw new ApiException.Gone("upload_expired", "This upload is no longer open; start a new one");
        }
        if (r.parts().size() != upload.getPartCount()) {
            throw new ApiException.BadRequest("parts_incomplete", "Expected " + upload.getPartCount() + " parts");
        }
        ObjectStorage.StoredObject stored;
        try {
            stored = storage.completeMultipart(upload.getObjectKey(), upload.getStorageUploadId(), r.parts().stream()
                .map(p -> new ObjectStorage.CompletedPart(p.partNumber(), p.etag())).toList());
        } catch (ObjectStorage.StorageMismatchException ex) {
            throw new ApiException.BadRequest("parts_mismatch", ex.getMessage());
        }
        upload.completed();

        if (stored.size() != upload.getDeclaredBytes()) {
            asset.rejected("size_mismatch", "Uploaded " + stored.size() + " bytes, declared " + upload.getDeclaredBytes());
            throw new ApiException.Unprocessable("size_mismatch", asset.getFailureDetail()).with("asset", AssetResponse.of(asset));
        }
        if (upload.getDeclaredSha256() != null && stored.sha256() != null && !upload.getDeclaredSha256().equals(stored.sha256())) {
            asset.rejected("checksum_mismatch", "The file's SHA-256 doesn't match the declared checksum");
            throw new ApiException.Unprocessable("checksum_mismatch", asset.getFailureDetail()).with("asset", AssetResponse.of(asset));
        }
        asset.uploaded(stored.key(), stored.versionId(), stored.size(), stored.sha256());

        if (asset.getType() == ContentAsset.Type.VIDEO) {
            jobs.enqueue(JobRequest.of(PROCESS_JOB, PROCESS_JOB + ":" + asset.getId() + ":" + stored.versionId(),
                new ProcessWork(asset.getId(), stored.versionId())).withMaxAttempts(5));
        } else if (!looksLike(asset)) {
            asset.rejected("invalid_file", "The file content doesn't match its declared type");
        } else {
            asset.readyAsIs();
        }
        return AssetResponse.of(asset);
    }

    /** Payload of the MEDIA_PROCESS job. */
    public record ProcessWork(UUID assetId, String sourceVersionId) {
    }

    @Transactional
    public void abort(CurrentActor actor, UUID uploadId) {
        MediaUpload upload = ownUpload(actor, uploadId);
        if (upload.getStatus() != MediaUpload.Status.INITIATED) {
            return;
        }
        storage.abortMultipart(upload.getObjectKey(), upload.getStorageUploadId());
        upload.aborted();
        ContentAsset asset = asset(upload.getContentAssetId());
        if (asset.getStatus() == ContentAsset.Status.PENDING_UPLOAD) {
            asset.cancelled();
        }
    }

    /** Abandoned uploads: storage parts are discarded and the never-uploaded asset is cancelled. */
    @Transactional
    public int expireAbandonedUploads() {
        List<MediaUpload> expired = uploads.findExpired(Instant.now());
        for (MediaUpload upload : expired) {
            storage.abortMultipart(upload.getObjectKey(), upload.getStorageUploadId());
            upload.expired();
            assets.findById(upload.getContentAssetId())
                .filter(a -> a.getStatus() == ContentAsset.Status.PENDING_UPLOAD)
                .ifPresent(ContentAsset::cancelled);
        }
        return expired.size();
    }

    // --- library -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AssetResponse> library(CurrentActor actor, UUID ownerOrganizationId, ContentAsset.Type type,
                                               ContentAsset.Status status, int page, int size) {
        if (!(actor.isPlatformSupport() && ownerOrganizationId == null)) {
            access.requireManageOwner(actor, ownerOrganizationId);
        }
        return PageResponse.of(assets.library(ownerOrganizationId, type, status,
            PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size))), AssetResponse::of);
    }

    @Transactional(readOnly = true)
    public AssetResponse get(CurrentActor actor, UUID assetId) {
        ContentAsset asset = asset(assetId);
        access.requireRead(actor, asset);
        List<AssetResponse.Rendition> current = asset.getType() != ContentAsset.Type.VIDEO ? null
            : renditions.findByContentAssetIdAndProcessingGenerationOrderByHeightDesc(assetId, asset.getProcessingGeneration())
                .stream().map(r -> new AssetResponse.Rendition(r.getHeight(), r.getWidth(), r.getBitrateKbps(), r.getSegmentCount()))
                .toList();
        return AssetResponse.of(asset, current);
    }

    /** Runs a FAILED video through the pipeline again from its stored source; no new upload is needed. */
    @Transactional
    public AssetResponse reprocess(CurrentActor actor, UUID assetId) {
        ContentAsset asset = asset(assetId);
        access.requireManage(actor, asset);
        if (asset.getType() != ContentAsset.Type.VIDEO || asset.getStatus() != ContentAsset.Status.FAILED) {
            throw new ApiException.Conflict("not_reprocessable", "Only failed videos can be reprocessed");
        }
        asset.requeueForProcessing();
        jobs.enqueue(JobRequest.of(PROCESS_JOB, PROCESS_JOB + ":" + assetId + ":reprocess:" + UUID.randomUUID(),
            new ProcessWork(assetId, asset.getSourceVersionId())).withMaxAttempts(5));
        return AssetResponse.of(asset);
    }

    @Transactional
    public AssetResponse rename(CurrentActor actor, UUID assetId, String title) {
        ContentAsset asset = asset(assetId);
        access.requireManage(actor, asset);
        asset.rename(title.trim());
        return AssetResponse.of(asset);
    }

    /** Withdraws an asset everywhere; lessons using it show it as unavailable. Published curricula aren't changed. */
    @Transactional
    public AssetResponse retire(CurrentActor actor, UUID assetId) {
        ContentAsset asset = asset(assetId);
        access.requireManage(actor, asset);
        asset.retire();
        return AssetResponse.of(asset);
    }

    /** Only assets no lesson references (draft or published) can be deleted; otherwise retire them. */
    @Transactional
    public void delete(CurrentActor actor, UUID assetId) {
        ContentAsset asset = asset(assetId);
        access.requireManage(actor, asset);
        if (assets.countLessonUses(assetId) > 0) {
            throw new ApiException.Conflict("asset_in_use", "Lessons use this asset; remove it from them or retire it");
        }
        if (asset.getStatus() == ContentAsset.Status.PROCESSING) {
            throw new ApiException.Conflict("asset_processing", "Wait for processing to finish");
        }
        assets.delete(asset);
    }

    // --- helpers -----------------------------------------------------------------------------------

    static long partSize(long bytes) {
        if (bytes <= SINGLE_PART_LIMIT) {
            return bytes;
        }
        long needed = Math.ceilDiv(bytes, MAX_PARTS);
        long size = Math.max(MIN_PART_SIZE, needed);
        long mb = 1024L * 1024;
        return Math.ceilDiv(size, mb) * mb;
    }

    /** A cheap magic-number check so a renamed file isn't served as a PDF or image. */
    private boolean looksLike(ContentAsset asset) {
        try (InputStream in = storage.open(asset.getSourceObjectKey())) {
            byte[] head = in.readNBytes(12);
            return switch (asset.getMimeType()) {
                case "application/pdf" -> startsWith(head, "%PDF-".getBytes());
                case "image/png" -> startsWith(head, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
                case "image/jpeg" -> startsWith(head, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                case "image/webp" -> head.length >= 12 && startsWith(head, "RIFF".getBytes())
                    && new String(head, 8, 4).equals("WEBP");
                default -> true;
            };
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private UploadResponse response(MediaUpload upload, ContentAsset asset) {
        List<UploadResponse.Part> parts = upload.getStatus() == MediaUpload.Status.INITIATED
            ? storage.listParts(upload.getObjectKey(), upload.getStorageUploadId()).stream()
                .map(p -> new UploadResponse.Part(p.partNumber(), p.size(), p.etag())).toList()
            : List.of();
        return new UploadResponse(upload.getId(), upload.getStatus(), upload.getPartSizeBytes(), upload.getPartCount(),
            upload.getExpiresAt(), parts, AssetResponse.of(asset));
    }

    private MediaUpload ownUpload(CurrentActor actor, UUID uploadId) {
        return uploads.findById(uploadId).filter(u -> u.getInitiatedBy().equals(actor.userId()))
            .orElseThrow(() -> new ApiException.NotFound("upload_not_found", "Upload not found"));
    }

    private MediaUpload ownOpenUpload(CurrentActor actor, UUID uploadId) {
        MediaUpload upload = ownUpload(actor, uploadId);
        if (!upload.isOpen(Instant.now())) {
            throw new ApiException.Gone("upload_expired", "This upload is no longer open");
        }
        return upload;
    }

    private ContentAsset asset(UUID assetId) {
        return assets.findById(assetId).orElseThrow(AssetAccess::notFound);
    }
}
