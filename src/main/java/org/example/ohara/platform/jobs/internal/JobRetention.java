package org.example.ohara.platform.jobs.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps the job table small and free of stale personal data (docs 03 §10.1): succeeded jobs go after 30 days, failed,
 * dead and cancelled ones after 180 (kept longer for investigation), and email jobs, whose payload holds an address and
 * the message, after 7. Pending and running jobs are never touched.
 * <p>
 * Purging frees idempotency keys. That's safe because every handler also checks the state it acts on (an asset
 * already READY, a reminder whose session moved), so a late duplicate trigger does nothing.
 */
@Component
public class JobRetention {

    private static final Logger log = LoggerFactory.getLogger(JobRetention.class);
    private static final int BATCH = 1000;

    private final JobStore store;
    private final Duration succeeded;
    private final Duration failed;
    private final Duration email;

    JobRetention(JobStore store,
                 @Value("${ohara.jobs.retention.succeeded:30d}") Duration succeeded,
                 @Value("${ohara.jobs.retention.failed:180d}") Duration failed,
                 @Value("${ohara.jobs.retention.email:7d}") Duration email) {
        this.store = store;
        this.succeeded = succeeded;
        this.failed = failed;
        this.email = email;
    }

    /** Deletes everything past retention, in batches; returns how many jobs were removed. */
    public int purge() {
        Instant now = Instant.now();
        int total = 0;
        int deleted;
        do {
            deleted = store.purgeFinished(now.minus(succeeded), now.minus(email), now.minus(failed), BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Purged {} finished job(s) past retention", total);
        }
        return total;
    }
}
