package org.example.ohara.live.api.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Absent fields stay as they are. Only upcoming sessions can be changed. */
public record UpdateLiveSessionRequest(@Size(min = 1, max = 200) String title, UUID hostUserId, UUID lessonId,
                                       Instant scheduledStart, Instant scheduledEnd, Boolean needsHd) {
}
