package org.example.ohara.platform.jobs.internal;

import org.example.ohara.platform.jobs.JobRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the processing_job table. Plain JDBC because claiming relies on FOR UPDATE SKIP LOCKED. */
@Repository
class JobStore {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;

    JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean insert(JobRequest request, String payloadJson) {
        // Immediate jobs use the database clock: claim compares against now() there, and app/DB clocks can skew.
        Timestamp runAfter = request.runAfter() == null ? null : Timestamp.from(request.runAfter());
        return jdbc.sql("""
                INSERT INTO processing_job (id, job_type, idempotency_key, priority, max_attempts, run_after, payload)
                VALUES (:id, :type, :key, :priority, :maxAttempts, COALESCE(CAST(:runAfter AS timestamptz), now()), CAST(:payload AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("id", UUID.randomUUID())
            .param("type", request.jobType())
            .param("key", request.idempotencyKey())
            .param("priority", request.priority())
            .param("maxAttempts", request.maxAttempts())
            .param("runAfter", runAfter)
            .param("payload", payloadJson)
            .update() == 1;
    }

    /** Atomically moves up to {@code limit} ready jobs to RUNNING for this worker; other workers skip locked rows. */
    List<JobRecord> claim(Collection<String> types, int limit, String workerId, Duration lease) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                WITH next AS (
                    SELECT id FROM processing_job
                    WHERE status = 'PENDING' AND run_after <= now() AND job_type IN (:types)
                    ORDER BY priority, run_after
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                UPDATE processing_job j
                SET status = 'RUNNING', locked_by = :worker, lease_until = now() + make_interval(secs => :leaseSeconds),
                    attempt = attempt + 1, updated_at = now()
                FROM next WHERE j.id = next.id
                RETURNING j.id, j.job_type, j.idempotency_key, j.status, j.attempt, j.max_attempts,
                          j.payload::text AS payload, j.last_error, j.run_after, j.created_at, j.finished_at
                """)
            .param("types", types)
            .param("limit", limit)
            .param("worker", workerId)
            .param("leaseSeconds", lease.toSeconds())
            .query(JobStore::map)
            .list();
    }

    void markSucceeded(UUID id, String workerId) {
        finish(id, workerId, "SUCCEEDED", null);
    }

    void markFailed(UUID id, String workerId, String error) {
        finish(id, workerId, "FAILED", error);
    }

    void markDead(UUID id, String workerId, String error) {
        finish(id, workerId, "DEAD", error);
    }

    void scheduleRetry(UUID id, String workerId, Instant runAfter, String error) {
        jdbc.sql("""
                UPDATE processing_job
                SET status = 'PENDING', run_after = :runAfter, locked_by = NULL, lease_until = NULL,
                    last_error = :error, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
            .param("id", id)
            .param("worker", workerId)
            .param("runAfter", Timestamp.from(runAfter))
            .param("error", truncate(error))
            .update();
    }

    /** Jobs whose worker died mid-run go back to PENDING (the attempt already counted), or DEAD if out of attempts. */
    int recoverExpiredLeases() {
        return jdbc.sql("""
                UPDATE processing_job
                SET status = CASE WHEN attempt >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    finished_at = CASE WHEN attempt >= max_attempts THEN now() ELSE NULL END,
                    last_error = 'Lease expired (worker ' || coalesce(locked_by, '?') || ' stopped responding)',
                    locked_by = NULL, lease_until = NULL, run_after = now(), updated_at = now()
                WHERE status = 'RUNNING' AND lease_until < now()
                """)
            .update();
    }

    Optional<JobRecord> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(JobStore::map).optional();
    }

    List<JobRecord> search(String type, String status, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE (CAST(:type AS varchar) IS NULL OR job_type = :type)
                   AND (CAST(:status AS varchar) IS NULL OR status = :status)
                 ORDER BY created_at DESC LIMIT :limit
                """)
            .param("type", type)
            .param("status", status)
            .param("limit", limit)
            .query(JobStore::map)
            .list();
    }

    /** Manual retry of a finished-unsuccessfully job: fresh attempt budget, runs immediately. */
    boolean retry(UUID id) {
        return jdbc.sql("""
                UPDATE processing_job
                SET status = 'PENDING', attempt = 0, run_after = now(), finished_at = NULL, updated_at = now()
                WHERE id = :id AND status IN ('FAILED', 'DEAD', 'CANCELLED')
                """)
            .param("id", id)
            .update() == 1;
    }

    boolean cancel(UUID id) {
        return jdbc.sql("""
                UPDATE processing_job SET status = 'CANCELLED', finished_at = now(), updated_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
            .param("id", id)
            .update() == 1;
    }

    private void finish(UUID id, String workerId, String status, String error) {
        jdbc.sql("""
                UPDATE processing_job
                SET status = :status, last_error = :error, locked_by = NULL, lease_until = NULL,
                    finished_at = now(), updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
            .param("id", id)
            .param("worker", workerId)
            .param("status", status)
            .param("error", truncate(error))
            .update();
    }

    int discard(String jobType, String payloadField, String value) {
        return jdbc.sql("""
                DELETE FROM processing_job
                WHERE job_type = :type AND status <> 'RUNNING' AND lower(payload ->> :field) = lower(:value)""")
            .param("type", jobType).param("field", payloadField).param("value", value)
            .update();
    }

    /**
     * Deletes up to {@code batch} finished jobs: succeeded ones finished before {@code succeededBefore} (email jobs,
     * which carry addresses and bodies, before {@code emailBefore}), failed, dead and cancelled ones before
     * {@code failedBefore}. Small batches keep each delete short; the caller loops.
     */
    int purgeFinished(Instant succeededBefore, Instant emailBefore, Instant failedBefore, int batch) {
        return jdbc.sql("""
                DELETE FROM processing_job WHERE id IN (
                    SELECT id FROM processing_job
                    WHERE (status = 'SUCCEEDED' AND finished_at < :succeeded)
                       OR (job_type = 'EMAIL_SEND' AND status IN ('SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED') AND finished_at < :email)
                       OR (status IN ('FAILED', 'DEAD', 'CANCELLED') AND finished_at < :failed)
                    LIMIT :batch)""")
            .param("succeeded", Timestamp.from(succeededBefore)).param("email", Timestamp.from(emailBefore))
            .param("failed", Timestamp.from(failedBefore)).param("batch", batch)
            .update();
    }

    private static final String SELECT = """
            SELECT id, job_type, idempotency_key, status, attempt, max_attempts, payload::text AS payload,
                   last_error, run_after, created_at, finished_at
            FROM processing_job""";

    private static JobRecord map(ResultSet rs, int row) throws SQLException {
        return new JobRecord(
            rs.getObject("id", UUID.class),
            rs.getString("job_type"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            rs.getString("payload"),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("run_after")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("finished_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
