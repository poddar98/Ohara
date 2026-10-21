package org.example.ohara.platform.jobs;

/** Enqueues jobs. Joins the caller's transaction, so a job exists only if the business change commits. */
public interface JobScheduler {

    /** @return true if a job was created, false if one with the same idempotency key already exists */
    boolean enqueue(JobRequest request);
}
