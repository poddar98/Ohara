package org.example.ohara.platform.jobs.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param runnerEnabled  whether this instance executes jobs (off on API-only nodes and in tests that drive the runner)
 * @param concurrency    jobs executed in parallel per poll (virtual threads); also the claim batch size
 * @param lease          how long a claimed job is reserved before another worker may take it over
 * @param retryBase      first retry delay; doubles per attempt with ±50% jitter
 * @param retryCap       longest retry delay
 */
@ConfigurationProperties("ohara.jobs")
record JobProperties(
    Boolean runnerEnabled,
    Duration pollInterval,
    Integer concurrency,
    Duration lease,
    Duration retryBase,
    Duration retryCap) {

    JobProperties {
        runnerEnabled = runnerEnabled == null || runnerEnabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        concurrency = concurrency == null ? 8 : concurrency;
        lease = lease == null ? Duration.ofMinutes(5) : lease;
        retryBase = retryBase == null ? Duration.ofSeconds(30) : retryBase;
        retryCap = retryCap == null ? Duration.ofHours(1) : retryCap;
    }
}
