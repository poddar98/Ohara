package org.example.ohara.live.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Streams the class recording through the CDN with signed cookies (set on the response), like recorded lessons. */
public record ReplayResponse(UUID sessionId, UUID assetId, String masterPlaylistUrl, Instant expiresAt, List<Caption> captions) {

    public record Caption(String language, String url) {
    }
}
