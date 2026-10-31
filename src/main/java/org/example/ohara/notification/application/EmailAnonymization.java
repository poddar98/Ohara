package org.example.ohara.notification.application;

import org.example.ohara.platform.jobs.JobScheduler;
import org.example.ohara.user.application.AccountEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A deleted person's emails, queued or already sent, are removed from the job table with the rest of their data,
 * rather than lingering until retention purges them.
 */
@Component
class EmailAnonymization {

    private final JobScheduler jobs;

    EmailAnonymization(JobScheduler jobs) {
        this.jobs = jobs;
    }

    @EventListener
    void on(AccountEvents.Anonymizing event) {
        jobs.discard(EmailOutbox.JOB_TYPE, "to", event.formerEmail());
    }
}
