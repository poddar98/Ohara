package org.example.ohara.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code onLegalHold}: a payment or refund dispute defers anonymization; the reason is shown to staff only. */
public record DeletionStatus(UUID userId, String status, Instant deletionRequestedAt, Instant deletionDueAt,
                             boolean onLegalHold, String legalHoldReason, Instant anonymizedAt) {
}
