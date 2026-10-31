package org.example.ohara.platform.jobs.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Queue health for alerting (docs 09 §8): jobs per type and status (PENDING, RUNNING, DEAD), and the age of the oldest
 * ready job per type (a growing backlog means workers can't keep up or are down). Refreshed by the job poller.
 */
@Component
public class JobQueueMetrics {

    private final JdbcClient jdbc;
    private final MultiGauge counts;
    private final MultiGauge oldestReadySeconds;

    JobQueueMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.counts = MultiGauge.builder("ohara.jobs.queue").description("Jobs by type and status").register(meters);
        this.oldestReadySeconds = MultiGauge.builder("ohara.jobs.oldest_ready_seconds")
            .description("Age of the oldest job that is ready to run").baseUnit("seconds").register(meters);
    }

    public void refresh() {
        List<MultiGauge.Row<?>> byStatus = jdbc.sql("""
                SELECT job_type, status, count(*) AS n FROM processing_job
                WHERE status IN ('PENDING', 'RUNNING', 'DEAD') GROUP BY job_type, status""")
            .query((rs, i) -> MultiGauge.Row.of(
                Tags.of("type", rs.getString("job_type"), "status", rs.getString("status")), (Number) rs.getLong("n")))
            .list().stream().<MultiGauge.Row<?>>map(r -> r).toList();
        counts.register(byStatus, true);
        List<MultiGauge.Row<?>> ages = jdbc.sql("""
                SELECT job_type, EXTRACT(EPOCH FROM now() - min(run_after)) AS age FROM processing_job
                WHERE status = 'PENDING' AND run_after <= now() GROUP BY job_type""")
            .query((rs, i) -> MultiGauge.Row.of(Tags.of("type", rs.getString("job_type")), (Number) rs.getDouble("age")))
            .list().stream().<MultiGauge.Row<?>>map(r -> r).toList();
        oldestReadySeconds.register(ages, true);
    }
}
