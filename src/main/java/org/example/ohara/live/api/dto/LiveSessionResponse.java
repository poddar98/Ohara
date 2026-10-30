package org.example.ohara.live.api.dto;

import java.time.Instant;
import java.util.UUID;

public record LiveSessionResponse(UUID id, UUID organizationId, UUID cohortId, UUID offeringId, UUID lessonId, String title,
                                  UUID hostUserId, Instant scheduledStart, Instant scheduledEnd, Instant joinOpensAt,
                                  String status, boolean needsHd, Instant actualStart, Instant actualEnd,
                                  String cancelledReason) {
}
