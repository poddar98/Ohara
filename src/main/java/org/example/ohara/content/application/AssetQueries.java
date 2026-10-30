package org.example.ohara.content.application;

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

    static final Duration DOWNLOAD_LINK_TTL = Duration.ofSeconds(60);

    private final ContentAssetRepository assets;
    private final ObjectStorage storage;
    private final CdnSigner cdn;

    AssetQueries(ContentAssetRepository assets, ObjectStorage storage, CdnSigner cdn) {
        this.assets = assets;
        this.storage = storage;
        this.cdn = cdn;
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
