package org.example.ohara.platform.jobs;

import java.time.Instant;
import java.util.Objects;

/**
 * A job to enqueue. {@code idempotencyKey} is globally unique: enqueuing the same key twice creates one job,
 * which is what makes retried or duplicated triggers harmless.
 */
public record JobRequest(String jobType, String idempotencyKey, Object payload, int maxAttempts, int priority, Instant runAfter) {

    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final int DEFAULT_PRIORITY = 5;

    public JobRequest {
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    public static JobRequest of(String jobType, String idempotencyKey, Object payload) {
        return new JobRequest(jobType, idempotencyKey, payload, DEFAULT_MAX_ATTEMPTS, DEFAULT_PRIORITY, null);
    }

    public JobRequest withMaxAttempts(int attempts) {
        return new JobRequest(jobType, idempotencyKey, payload, attempts, priority, runAfter);
    }

    /** Lower runs first. */
    public JobRequest withPriority(int value) {
        return new JobRequest(jobType, idempotencyKey, payload, maxAttempts, value, runAfter);
    }

    public JobRequest runAfter(Instant when) {
        return new JobRequest(jobType, idempotencyKey, payload, maxAttempts, priority, when);
    }
}
