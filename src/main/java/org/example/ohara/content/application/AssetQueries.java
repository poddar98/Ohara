package org.example.ohara.content.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.content.domain.ContentAsset;
import org.example.ohara.content.infrastructure.persistence.ContentAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only asset facts and delivery links for other modules. Callers authorize the learner first. */
@Service
public class AssetQueries {

    public record AssetFacts(UUID id, UUID ownerOrganizationId, ContentAsset.Type type, ContentAsset.Status status,
                             String title, String fileName, Long durationMs) {
    }

    /**
     * What AI processing needs about a video. {@code sourceRef} identifies the exact source bytes (storage version,
     * else checksum, else a hash of the key): the same ref means the same audio, so it must not be transcribed twice.
     */
    public record MediaSource(UUID assetId, UUID ownerOrganizationId, ContentAsset.Status status, String sourceKey,
                              String sourceRef, long durationMs, String deliveryPrefix) {

        public boolean ready() {
            return status == ContentAsset.Status.READY;
        }
    }

    static final Duration DOWNLOAD_LINK_TTL = Duration.ofSeconds(60);

    private final ContentAssetRepository assets;
    private final ObjectStorage storage;
    private final CdnSigner cdn;
    private final AssetAccess access;

    AssetQueries(ContentAssetRepository assets, ObjectStorage storage, CdnSigner cdn, AssetAccess access) {
        this.assets = assets;
        this.storage = storage;
        this.cdn = cdn;
        this.access = access;
    }

    /** Videos only; empty for other types. */
    @Transactional(readOnly = true)
    public Optional<MediaSource> mediaSource(UUID assetId) {
        return assets.findById(assetId)
            .filter(a -> a.getType() == ContentAsset.Type.VIDEO && a.getSourceObjectKey() != null)
            .map(a -> new MediaSource(a.getId(), a.getOwnerOrganizationId(), a.getStatus(), a.getSourceObjectKey(),
                sourceRef(a), a.getDurationMs() == null ? 0 : a.getDurationMs(), a.getDeliveryPrefix()));
    }

    /** The library's read rule (owner org staff, platform staff); 404 otherwise. */
    @Transactional(readOnly = true)
    public AssetFacts requireRead(CurrentActor actor, UUID assetId) {
        ContentAsset asset = assets.findById(assetId).orElseThrow(AssetAccess::notFound);
        access.requireRead(actor, asset);
        return facts(asset);
    }

    /** The library's manage rule (owner org authors, platform admins). */
    @Transactional(readOnly = true)
    public AssetFacts requireManage(CurrentActor actor, UUID assetId) {
        ContentAsset asset = assets.findById(assetId).orElseThrow(AssetAccess::notFound);
        access.requireManage(actor, asset);
        return facts(asset);
    }

    private static String sourceRef(ContentAsset a) {
        if (a.getSourceVersionId() != null) {
            return "v:" + a.getSourceVersionId();
        }
        if (a.getSourceSha256() != null) {
            return "sha256:" + a.getSourceSha256();
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(a.getSourceObjectKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "key:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where to start streaming, and the cookies that unlock exactly this video's files until {@code expiresAt}. */
    public record PlaybackGrant(String masterPlaylistUrl, java.util.List<CdnSigner.Cookie> cookies) {
    }

    /** Empty unless the asset is a READY video. Callers must have authorized the viewer. */
    @Transactional(readOnly = true)
    public Optional<PlaybackGrant> playback(UUID assetId, java.time.Instant expiresAt) {
        return assets.findById(assetId)
            .filter(a -> a.getType() == ContentAsset.Type.VIDEO && a.getStatus() == ContentAsset.Status.READY)
            .map(a -> {
                String master = a.getMasterPlaylistKey();
                String prefix = master.substring(0, master.lastIndexOf('/') + 1);
                return new PlaybackGrant(cdn.urlOf(master), cdn.sign(prefix, expiresAt));
            });
    }

    @Transactional(readOnly = true)
    public Optional<AssetFacts> asset(UUID assetId) {
        return assets.findById(assetId).map(AssetQueries::facts);
    }

    @Transactional(readOnly = true)
    public Map<UUID, AssetFacts> assets(Collection<UUID> ids) {
        return assets.findAllById(ids).stream().map(AssetQueries::facts)
            .collect(Collectors.toMap(AssetFacts::id, Function.identity()));
    }

    /** A 60-second download link for a READY document; empty otherwise. */
    @Transactional(readOnly = true)
    public Optional<URI> documentLink(UUID assetId) {
        return assets.findById(assetId)
            .filter(a -> a.getType() == ContentAsset.Type.DOCUMENT && a.getStatus() == ContentAsset.Status.READY)
            .map(a -> storage.presignGet(a.getSourceObjectKey(), DOWNLOAD_LINK_TTL, a.getOriginalFilename()));
    }

    private static AssetFacts facts(ContentAsset a) {
        return new AssetFacts(a.getId(), a.getOwnerOrganizationId(), a.getType(), a.getStatus(), a.getTitle(),
            a.getOriginalFilename(), a.getDurationMs());
    }
}
