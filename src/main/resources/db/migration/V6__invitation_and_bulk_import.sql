-- Invitations to join an organization (and optionally a cohort). The token is emailed; only its hash is stored.
CREATE TABLE invitation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    cohort_id UUID NULL,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(32) NOT NULL,
    first_name VARCHAR(100) NULL,
    last_name VARCHAR(100) NULL,
    external_ref VARCHAR(64) NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    invited_by UUID NOT NULL,
    send_count INT NOT NULL DEFAULT 1,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_by_user_id UUID NULL,
    accepted_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_invitation_org FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invitation_cohort FOREIGN KEY (cohort_id, organization_id) REFERENCES cohort (id, organization_id),
    CONSTRAINT fk_invitation_accepted_by FOREIGN KEY (accepted_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_invitation_token UNIQUE (token_hash),
    CONSTRAINT ck_invitation_role CHECK (role IN ('ORG_ADMIN', 'INSTRUCTOR', 'LEARNER')),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_invitation_cohort_learner CHECK (cohort_id IS NULL OR role = 'LEARNER')
);
-- One open invitation per person per organization; re-inviting rotates the token on the same row.
CREATE UNIQUE INDEX uq_invitation_pending ON invitation (organization_id, lower(email)) WHERE status = 'PENDING';
CREATE INDEX ix_invitation_org_status ON invitation (organization_id, status, created_at DESC);

-- Bulk import of people into an organization from a CSV file.
CREATE TABLE bulk_import_job (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    cohort_id UUID NULL,
    mode VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'LEARNER',
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'VALIDATED',
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    succeeded_rows INT NOT NULL DEFAULT 0,
    failed_rows INT NOT NULL DEFAULT 0,
    skipped_rows INT NOT NULL DEFAULT 0,
    uploaded_by UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NULL,
    finished_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_bulk_import_org FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bulk_import_cohort FOREIGN KEY (cohort_id, organization_id) REFERENCES cohort (id, organization_id),
    CONSTRAINT ck_bulk_import_mode CHECK (mode IN ('INVITE', 'CREATE_ACCOUNTS')),
    CONSTRAINT ck_bulk_import_role CHECK (role IN ('ORG_ADMIN', 'INSTRUCTOR', 'LEARNER')),
    CONSTRAINT ck_bulk_import_cohort_learner CHECK (cohort_id IS NULL OR role = 'LEARNER'),
    CONSTRAINT ck_bulk_import_status CHECK (status IN
        ('VALIDATED', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED'))
);
-- The same file can't be imported twice into an organization unless the earlier import was cancelled.
CREATE UNIQUE INDEX uq_bulk_import_file ON bulk_import_job (organization_id, file_sha256) WHERE status <> 'CANCELLED';
CREATE INDEX ix_bulk_import_org ON bulk_import_job (organization_id, created_at DESC);

CREATE TABLE bulk_import_row (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    row_number INT NOT NULL,
    email VARCHAR(320) NULL,
    first_name VARCHAR(100) NULL,
    last_name VARCHAR(100) NULL,
    external_ref VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_detail VARCHAR(500) NULL,
    user_id UUID NULL,
    invitation_id UUID NULL,
    processed_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_bulk_import_row_job FOREIGN KEY (job_id) REFERENCES bulk_import_job (id) ON DELETE CASCADE,
    CONSTRAINT uq_bulk_import_row UNIQUE (job_id, row_number),
    CONSTRAINT ck_bulk_import_row_status CHECK (status IN
        ('PENDING', 'INVITED', 'CREATED', 'LINKED_EXISTING', 'SKIPPED_DUPLICATE', 'INVALID', 'FAILED', 'CANCELLED'))
);
CREATE INDEX ix_bulk_import_row_job_status ON bulk_import_row (job_id, status, row_number);
