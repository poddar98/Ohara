package org.example.ohara.platform.jobs;

/** Enqueues jobs. Joins the caller's transaction, so a job exists only if the business change commits. */
public interface JobScheduler {

    /** @return true if a job was created, false if one with the same idempotency key already exists */
    boolean enqueue(JobRequest request);

    /**
     * Deletes jobs of {@code jobType} whose JSON payload has {@code payloadField} equal (ignoring case) to {@code value},
     * unless they're running right now. For removing personal data, e.g. a deleted person's queued and sent emails.
     *
     * @return how many jobs were deleted
     */
    int discard(String jobType, String payloadField, String value);
}
