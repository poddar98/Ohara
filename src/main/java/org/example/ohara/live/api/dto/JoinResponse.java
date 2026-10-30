package org.example.ohara.live.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything a player needs: a per-viewer playback URL+token and a chat token, both short-lived. Rejoin (which
 * re-checks access) before {@code playbackExpiresAt}. Learners send heartbeats every {@code heartbeatIntervalSeconds}.
 */
public record JoinResponse(UUID sessionId, String status, String role, String playbackUrl, String playbackToken,
                           Instant playbackExpiresAt, String chatRoom, String chatToken, Instant chatExpiresAt,
                           Integer heartbeatIntervalSeconds) {
}
