package org.example.ohara.platform.jobs.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.ohara.platform.jobs.JobHandler;
import org.example.ohara.platform.jobs.NonRetryableJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claims ready jobs and runs their handlers. Each poll claims at most {@code concurrency} jobs and waits for them,
 * so in-flight work is bounded; jobs run on virtual threads because handlers are dominated by blocking I/O.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final JobProperties properties;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;
    private final Map<String, JobHandler<?>> handlers;
    private final String workerId;

    JobRunner(JobStore store, JobProperties properties, JsonMapper jsonMapper, MeterRegistry meters,
              ObjectProvider<JobHandler<?>> handlers) {
        this.store = store;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
        this.handlers = handlers.orderedStream().collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.workerId = workerName();
    }

    /** One poll: recover dead workers' jobs, then claim and run a batch. Returns how many jobs ran. */
    public int runOnce() {
        int recovered = store.recoverExpiredLeases();
        if (recovered > 0) {
            log.warn("Recovered {} job(s) whose lease expired", recovered);
        }

        List<JobRecord> claimed = store.claim(handlers.keySet(), properties.concurrency(), workerId, properties.lease());
        if (claimed.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claimed.forEach(job -> executor.submit(() -> execute(job)));
        } // close() waits for the batch
        return claimed.size();
    }

    /** Runs polls until nothing is ready. For tests and manual draining; retries scheduled in the future are not waited for. */
    public int drain() {
        int total = 0;
        int ran;
        while ((ran = runOnce()) > 0) {
            total += ran;
        }
        return total;
    }

    private void execute(JobRecord job) {
        JobHandler<?> handler = handlers.get(job.jobType());
        long started = System.nanoTime();
        String outcome;
        try {
            invoke(handler, job.payload());
            store.markSucceeded(job.id(), workerId);
            outcome = "succeeded";
        } catch (NonRetryableJobException ex) {
            log.error("Job {} ({}) failed permanently: {}", job.id(), job.jobType(), ex.getMessage());
            store.markFailed(job.id(), workerId, describe(ex));
            outcome = "failed";
        } catch (Exception ex) {
            if (job.attempt() >= job.maxAttempts()) {
                log.error("Job {} ({}) exhausted {} attempts", job.id(), job.jobType(), job.maxAttempts(), ex);
                store.markDead(job.id(), workerId, describe(ex));
                outcome = "dead";
            } else {
                Instant next = Instant.now().plus(backoff(job.attempt()));
                log.warn("Job {} ({}) attempt {}/{} failed, retrying at {}: {}",
                    job.id(), job.jobType(), job.attempt(), job.maxAttempts(), next, ex.toString());
                store.scheduleRetry(job.id(), workerId, next, describe(ex));
                outcome = "retry";
            }
        }
        meters.timer("ohara.jobs.execution", "type", job.jobType(), "outcome", outcome)
            .record(Duration.ofNanos(System.nanoTime() - started));
    }

    @SuppressWarnings("unchecked")
    private <P> void invoke(JobHandler<P> handler, String payloadJson) throws Exception {
        P payload;
        try {
            payload = jsonMapper.readValue(payloadJson, handler.payloadType());
        } catch (RuntimeException ex) {
            throw new NonRetryableJobException("Unreadable payload: " + ex.getMessage(), ex);
        }
        handler.handle(payload);
    }

    /** Exponential backoff with full ±50% jitter so retries of a failed batch don't hit a provider in lockstep. */
    Duration backoff(int attempt) {
        long baseMillis = properties.retryBase().toMillis();
        long capMillis = properties.retryCap().toMillis();
        long exponential = baseMillis << Math.min(attempt - 1, 20);
        long bounded = Math.min(capMillis, exponential);
        double jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        return Duration.ofMillis(Math.min(capMillis, (long) (bounded * jitter)));
    }

    private static String describe(Exception ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private static String workerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
