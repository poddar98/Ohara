package org.example.ohara.platform.jobs;

import org.example.ohara.platform.jobs.internal.JobRetention;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobRetentionIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired JobRetention retention;
    @Autowired JobScheduler scheduler;

    private String job(String type, String status, String finishedAgo, String to) {
        String key = type + ":" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO processing_job (id, job_type, idempotency_key, status, payload, finished_at, run_after)
                VALUES (gen_random_uuid(), :type, :key, :status, CAST(:payload AS jsonb),
                        CASE WHEN :ago = '' THEN NULL ELSE now() - CAST(:ago AS interval) END, now() - interval '400 days')""")
            .param("type", type).param("key", key).param("status", status).param("ago", finishedAgo)
            .param("payload", to == null ? "{}" : "{\"to\":\"" + to + "\",\"subject\":\"s\",\"body\":\"b\"}")
            .update();
        return key;
    }

    private List<String> remaining(List<String> keys) {
        return jdbc.sql("SELECT idempotency_key FROM processing_job WHERE idempotency_key IN (:keys)")
            .param("keys", keys).query(String.class).list();
    }

    @Test
    void finishedJobsArePurgedAfterTheirRetentionAndLiveOnesNever() {
        String oldSucceeded = job("RETENTION_TEST", "SUCCEEDED", "31 days", null);
        String recentSucceeded = job("RETENTION_TEST", "SUCCEEDED", "1 day", null);
        String oldEmail = job("EMAIL_SEND", "SUCCEEDED", "8 days", "someone@example.com");
        String recentEmail = job("EMAIL_SEND", "SUCCEEDED", "2 days", "someone@example.com");
        String deadMonthOld = job("RETENTION_TEST", "DEAD", "31 days", null);
        String deadHalfYearOld = job("RETENTION_TEST", "DEAD", "181 days", null);
        String ancientPending = job("RETENTION_TEST", "PENDING", "", null);

        retention.purge();

        assertThat(remaining(List.of(oldSucceeded, recentSucceeded, oldEmail, recentEmail, deadMonthOld, deadHalfYearOld, ancientPending)))
            .containsExactlyInAnyOrder(recentSucceeded, recentEmail, deadMonthOld, ancientPending);
        jdbc.sql("DELETE FROM processing_job WHERE idempotency_key IN (:keys)")
            .param("keys", List.of(recentSucceeded, recentEmail, deadMonthOld, ancientPending)).update();
    }

    @Test
    void personalEmailJobsCanBeDiscardedOnRequest() {
        String address = "Person-" + UUID.randomUUID() + "@Example.com";
        String sent = job("EMAIL_SEND", "SUCCEEDED", "1 day", address);
        String queued = job("EMAIL_SEND", "PENDING", "", address.toLowerCase());
        String running = job("EMAIL_SEND", "RUNNING", "", address);
        String someoneElse = job("EMAIL_SEND", "SUCCEEDED", "1 day", "other@example.com");

        assertThat(scheduler.discard("EMAIL_SEND", "to", address)).isEqualTo(2);

        assertThat(remaining(List.of(sent, queued, running, someoneElse))).containsExactlyInAnyOrder(running, someoneElse);
        jdbc.sql("DELETE FROM processing_job WHERE idempotency_key IN (:keys)").param("keys", List.of(running, someoneElse)).update();
    }
}
