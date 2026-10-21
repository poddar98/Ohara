package org.example.ohara.platform.jobs.internal;

import org.example.ohara.platform.jobs.JobHandler;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.example.ohara.platform.jobs.NonRetryableJobException;
import org.example.ohara.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobRunnerIntegrationTest {

    static final String FLAKY = "TEST_FLAKY";
    static final String POISON = "TEST_POISON";

    /** Payload for test jobs: fail this many times before succeeding. */
    record Work(String key, int failuresBeforeSuccess) {
    }

    /** Executions per payload key. */
    static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class TestHandlers {

        @Bean
        JobHandler<Work> flakyHandler() {
            return new JobHandler<>() {
                public String jobType() { return FLAKY; }
                public Class<Work> payloadType() { return Work.class; }
                public void handle(Work work) {
                    int run = EXECUTIONS.computeIfAbsent(work.key(), k -> new AtomicInteger()).incrementAndGet();
                    if (run <= work.failuresBeforeSuccess()) {
                        throw new IllegalStateException("provider timeout #" + run);
                    }
                }
            };
        }

        @Bean
        JobHandler<Work> poisonHandler() {
            return new JobHandler<>() {
                public String jobType() { return POISON; }
                public Class<Work> payloadType() { return Work.class; }
                public void handle(Work work) {
                    EXECUTIONS.computeIfAbsent(work.key(), k -> new AtomicInteger()).incrementAndGet();
                    throw new NonRetryableJobException("unsupported codec");
                }
            };
        }
    }

    @Autowired JobScheduler scheduler;
    @Autowired JobRunner runner;
    @Autowired JobStore store;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mockMvc;
    @Autowired PasswordEncoder passwordEncoder;
    TestUsers users;

    @BeforeEach
    void setUpUsers() {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private JobRecord job(String idempotencyKey) {
        UUID id = jdbc.sql("SELECT id FROM processing_job WHERE idempotency_key = :key")
            .param("key", idempotencyKey).query(UUID.class).single();
        return store.find(id).orElseThrow();
    }

    private void makeDue(String idempotencyKey) {
        jdbc.sql("UPDATE processing_job SET run_after = now() WHERE idempotency_key = :key")
            .param("key", idempotencyKey).update();
    }

    private int executions(String workKey) {
        return EXECUTIONS.getOrDefault(workKey, new AtomicInteger()).get();
    }

    @Test
    void enqueuedJobRunsOnceAndSucceeds() {
        String key = key();
        scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 0)));

        runner.drain();

        assertThat(job(key).status()).isEqualTo("SUCCEEDED");
        assertThat(job(key).attempt()).isEqualTo(1);
        assertThat(job(key).finishedAt()).isNotNull();
        assertThat(executions(key)).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyCreatesOneJob() {
        String key = key();

        assertThat(scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 0)))).isTrue();
        assertThat(scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 0)))).isFalse();
        runner.drain();

        assertThat(executions(key)).isEqualTo(1);
    }

    @Test
    void jobEnqueuedInARolledBackTransactionNeverExists() {
        String key = key();

        tx.executeWithoutResult(status -> {
            scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 0)));
            status.setRollbackOnly();
        });

        assertThat(jdbc.sql("SELECT count(*) FROM processing_job WHERE idempotency_key = :key")
            .param("key", key).query(Integer.class).single()).isZero();
    }

    @Test
    void retryableFailureIsRetriedLaterWithBackoff() {
        String key = key();
        scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 1)));

        runner.drain();

        JobRecord afterFailure = job(key);
        assertThat(afterFailure.status()).isEqualTo("PENDING");
        assertThat(afterFailure.attempt()).isEqualTo(1);
        assertThat(afterFailure.lastError()).contains("provider timeout #1");
        // 30 s base delay with ±50% jitter; not run again by the same drain.
        assertThat(afterFailure.runAfter()).isAfter(Instant.now().plusSeconds(10));

        makeDue(key);
        runner.drain();

        assertThat(job(key).status()).isEqualTo("SUCCEEDED");
        assertThat(job(key).attempt()).isEqualTo(2);
        assertThat(executions(key)).isEqualTo(2);
    }

    @Test
    void jobIsDeadAfterExhaustingAttempts() {
        String key = key();
        scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 99)).withMaxAttempts(2));

        runner.drain();
        makeDue(key);
        runner.drain();

        assertThat(job(key).status()).isEqualTo("DEAD");
        assertThat(job(key).attempt()).isEqualTo(2);
        assertThat(executions(key)).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureFailsImmediately() {
        String key = key();
        scheduler.enqueue(JobRequest.of(POISON, key, new Work(key, 0)));

        runner.drain();

        assertThat(job(key).status()).isEqualTo("FAILED");
        assertThat(job(key).lastError()).contains("unsupported codec");
        assertThat(executions(key)).isEqualTo(1);
    }

    @Test
    void unreadablePayloadFailsWithoutRetry() {
        String key = key();
        scheduler.enqueue(JobRequest.of(FLAKY, key, Map.of("failuresBeforeSuccess", "not-a-number")));

        runner.drain();

        assertThat(job(key).status()).isEqualTo("FAILED");
        assertThat(job(key).lastError()).contains("Unreadable payload");
    }

    @Test
    void jobOfACrashedWorkerIsRecoveredAndRerun() {
        String key = key();
        scheduler.enqueue(JobRequest.of(FLAKY, key, new Work(key, 0)));
        // Simulate a worker that claimed the job and died: RUNNING with an expired lease.
        jdbc.sql("""
                UPDATE processing_job SET status = 'RUNNING', attempt = 1, locked_by = 'dead-worker',
                       lease_until = now() - interval '1 minute' WHERE idempotency_key = :key
                """).param("key", key).update();

        runner.drain();

        assertThat(job(key).status()).isEqualTo("SUCCEEDED");
        assertThat(job(key).attempt()).isEqualTo(2);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        String prefix = key();
        for (int i = 0; i < 40; i++) {
            scheduler.enqueue(JobRequest.of(FLAKY, prefix + "-" + i, new Work(prefix + "-" + i, 0)).withPriority(0));
        }

        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<JobRecord>>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String worker = "worker-" + w;
                results.add(pool.submit(() -> {
                    start.await();
                    return store.claim(Set.of(FLAKY), 15, worker, Duration.ofMinutes(5));
                }));
            }
            start.countDown();
        }

        Set<UUID> seen = new HashSet<>();
        int total = 0;
        for (Future<List<JobRecord>> result : results) {
            for (JobRecord job : result.get()) {
                total++;
                assertThat(seen.add(job.id())).as("job %s claimed twice", job.id()).isTrue();
            }
        }
        assertThat(total).isEqualTo(seen.size());
        // Release the claims so these jobs don't linger as RUNNING.
        jdbc.sql("UPDATE processing_job SET status = 'CANCELLED' WHERE idempotency_key LIKE :p").param("p", prefix + "%").update();
    }

    @Test
    void adminCanListAndRetryJobsButRegularUsersCannot() throws Exception {
        String key = key();
        scheduler.enqueue(JobRequest.of(POISON, key, new Work(key, 0)));
        runner.drain();
        UUID jobId = job(key).id();

        mockMvc.perform(get("/api/admin/jobs").header("Authorization", users.user().bearer()))
            .andExpect(status().isForbidden());

        String adminToken = users.platformAdmin().token();
        mockMvc.perform(get("/api/admin/jobs").param("type", POISON).param("status", "FAILED")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(jobId)).exists());

        mockMvc.perform(post("/api/admin/jobs/{id}/retry", jobId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.attempt").value(0));

        mockMvc.perform(post("/api/admin/jobs/{id}/retry", jobId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("job_not_retryable"));
    }
}
