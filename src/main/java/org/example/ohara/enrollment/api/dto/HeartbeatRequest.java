package org.example.ohara.enrollment.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Sent by the player every ~20 s and on pause/seek/exit. {@code watchedDeltaMs} is time actually watched since the
 * previous heartbeat; the server credits at most the real time that passed. {@code clientTs} orders heartbeats.
 */
public record HeartbeatRequest(@PositiveOrZero Long positionMs, @Positive Long durationMs,
                               @PositiveOrZero @Max(600_000) long watchedDeltaMs, Instant clientTs) {
}
