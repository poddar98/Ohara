-- Durable background work. Rows are inserted in the same transaction as the business change that needs them
-- (transactional outbox), claimed with FOR UPDATE SKIP LOCKED, and retried with backoff.
CREATE TABLE processing_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    priority SMALLINT NOT NULL DEFAULT 5,
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    run_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by VARCHAR(64) NULL,
    lease_until TIMESTAMPTZ NULL,
    payload JSONB NOT NULL DEFAULT '{}',
    last_error VARCHAR(2000) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_processing_job_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_processing_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')),
    CONSTRAINT ck_processing_job_attempts CHECK (attempt >= 0 AND max_attempts > 0)
);

-- Claim query: next ready jobs by priority.
CREATE INDEX ix_processing_job_ready ON processing_job (priority, run_after) WHERE status = 'PENDING';
-- Lease recovery: running jobs whose worker died.
CREATE INDEX ix_processing_job_running ON processing_job (lease_until) WHERE status = 'RUNNING';
-- Admin search.
CREATE INDEX ix_processing_job_type_status ON processing_job (job_type, status, created_at DESC);
