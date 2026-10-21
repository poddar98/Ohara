package org.example.ohara.notification.application;

import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Durable email: the message is stored as an EMAIL_SEND job in the caller's transaction and delivered by the job
 * runner with retries. A rolled-back transaction therefore never sends, and a provider outage only delays.
 */
@Component
public class EmailOutbox {

    public static final String JOB_TYPE = "EMAIL_SEND";
    static final int MAX_ATTEMPTS = 8;

    private final JobScheduler jobs;

    public EmailOutbox(JobScheduler jobs) {
        this.jobs = jobs;
    }

    /** @param dedupeKey identifies this logical email so a repeated trigger doesn't send it twice */
    public void enqueue(EmailMessage message, String dedupeKey) {
        jobs.enqueue(JobRequest.of(JOB_TYPE, JOB_TYPE + ":" + dedupeKey, message).withMaxAttempts(MAX_ATTEMPTS));
    }

    /** For one-off notices where a repeat would be a separate email anyway. */
    public void enqueue(EmailMessage message) {
        enqueue(message, UUID.randomUUID().toString());
    }
}
