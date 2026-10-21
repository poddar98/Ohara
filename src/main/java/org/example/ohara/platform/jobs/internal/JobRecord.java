package org.example.ohara.platform.jobs.internal;

import java.time.Instant;
import java.util.UUID;

record JobRecord(
    UUID id,
    String jobType,
    String idempotencyKey,
    String status,
    int attempt,
    int maxAttempts,
    String payload,
    String lastError,
    Instant runAfter,
    Instant createdAt,
    Instant finishedAt) {
}
