package org.example.ohara.live.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** {@code hostUserId} defaults to the caller; {@code lessonId} optionally ties the class to a lesson of the course. */
public record ScheduleLiveSessionRequest(@NotNull UUID offeringId, UUID lessonId, @NotBlank @Size(max = 200) String title,
                                         UUID hostUserId, @NotNull Instant scheduledStart, @NotNull Instant scheduledEnd,
                                         Boolean needsHd) {
}
