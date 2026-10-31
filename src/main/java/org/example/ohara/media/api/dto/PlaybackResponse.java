package org.example.ohara.media.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Point the player at {@code masterPlaylistUrl}; the signed cookies set with this response authorize its files.
 * Call refresh at {@code refreshAt}. Report progress to the enrollment's progress endpoint with {@code lessonKey}.
 */
public record PlaybackResponse(UUID sessionId, String masterPlaylistUrl, Instant expiresAt, Instant refreshAt,
                               Long resumePositionMs, UUID enrollmentId, UUID lessonKey, List<Caption> captions) {

    /** A sidecar WebVTT track, readable with the same cookies as the video. */
    public record Caption(String language, String url) {
    }
}
