package org.example.ohara.platform.jobs;

/**
 * Executes one job type. Delivery is at-least-once: a job may run again after a crash or lease expiry,
 * so handlers must be idempotent (or tolerate a rare duplicate, like an email).
 * Throw {@link NonRetryableJobException} for failures a retry can't fix; anything else is retried with backoff.
 */
public interface JobHandler<P> {

    String jobType();

    Class<P> payloadType();

    void handle(P payload) throws Exception;
}
