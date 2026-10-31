package org.example.ohara.ai.application;

import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.example.ohara.content.application.CdnSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI facts other modules need. Callers authorize the viewer first. */
@Service
public class AiQueries {

    /** A sidecar caption track; the URL is covered by the same CDN cookies as the video. */
    public record CaptionTrack(String language, String url) {
    }

    private final AiStore store;
    private final CdnSigner cdn;
    private final JsonMapper json;

    AiQueries(AiStore store, CdnSigner cdn, JsonMapper json) {
        this.store = store;
        this.cdn = cdn;
        this.json = json;
    }

    /** Approved captions of a video (empty when none). */
    @Transactional(readOnly = true)
    public List<CaptionTrack> captions(UUID assetId) {
        return store.approved(assetId, "CAPTIONS").stream()
            .filter(a -> a.objectKey() != null)
            .map(a -> new CaptionTrack(String.valueOf(json.readValue(a.content(), Map.class).get("language")), cdn.urlOf(a.objectKey())))
            .toList();
    }
}
