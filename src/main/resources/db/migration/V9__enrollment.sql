-- How a course is delivered: to a college cohort (B2B) or publicly, with access granted by the contract team (B2C).
CREATE TABLE course_offering (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL,
    organization_id UUID NULL,
    cohort_id UUID NULL,
    title VARCHAR(200) NULL,
    version_policy VARCHAR(24) NOT NULL DEFAULT 'LATEST_ON_ENROLL',
    pinned_course_version_id UUID NULL,
    starts_at TIMESTAMPTZ NULL,
    ends_at TIMESTAMPTZ NULL,
    access_duration_days INT NULL,
    list_price_minor BIGINT NULL,
    currency CHAR(3) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_offering_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_offering_org FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_offering_cohort FOREIGN KEY (cohort_id, organization_id) REFERENCES cohort (id, organization_id),
    CONSTRAINT fk_offering_pinned_version FOREIGN KEY (pinned_course_version_id) REFERENCES course_version (id),
    CONSTRAINT ck_offering_channel CHECK (channel IN ('B2B', 'B2C')),
    CONSTRAINT ck_offering_channel_org CHECK ((channel = 'B2B') = (organization_id IS NOT NULL)),
    CONSTRAINT ck_offering_cohort_b2b CHECK (cohort_id IS NULL OR channel = 'B2B'),
    CONSTRAINT ck_offering_policy CHECK (version_policy IN ('LATEST_ON_ENROLL', 'PINNED')),
    CONSTRAINT ck_offering_pinned CHECK ((version_policy = 'PINNED') = (pinned_course_version_id IS NOT NULL)),
    CONSTRAINT ck_offering_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_offering_duration CHECK (access_duration_days IS NULL OR access_duration_days > 0),
    CONSTRAINT ck_offering_price CHECK (list_price_minor IS NULL OR (list_price_minor >= 0 AND currency IS NOT NULL)),
    CONSTRAINT ck_offering_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'ARCHIVED'))
);
-- A course is offered to a cohort at most once at a time.
CREATE UNIQUE INDEX uq_offering_course_cohort ON course_offering (course_id, cohort_id)
    WHERE cohort_id IS NOT NULL AND status <> 'ARCHIVED';
CREATE INDEX ix_offering_org_status ON course_offering (organization_id, status);
CREATE INDEX ix_offering_cohort_open ON course_offering (cohort_id) WHERE status = 'OPEN';
CREATE INDEX ix_offering_channel_status ON course_offering (channel, status);

-- A learner's relationship with an offering, pinned to the course version they study.
CREATE TABLE enrollment (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    course_offering_id UUID NOT NULL,
    course_id UUID NOT NULL,
    organization_id UUID NULL,
    course_version_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    ended_at TIMESTAMPTZ NULL,
    progress_percent NUMERIC(5, 2) NOT NULL DEFAULT 0,
    last_activity_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_enrollment_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollment_offering FOREIGN KEY (course_offering_id) REFERENCES course_offering (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollment_version FOREIGN KEY (course_version_id) REFERENCES course_version (id) ON DELETE RESTRICT,
    -- The idempotency anchor for cohort fan-out and staff grants alike.
    CONSTRAINT uq_enrollment_user_offering UNIQUE (user_id, course_offering_id),
    CONSTRAINT ck_enrollment_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'COMPLETED', 'EXPIRED', 'REVOKED', 'CANCELLED')),
    CONSTRAINT ck_enrollment_progress CHECK (progress_percent BETWEEN 0 AND 100)
);
CREATE INDEX ix_enrollment_user_status ON enrollment (user_id, status);
CREATE INDEX ix_enrollment_user_course ON enrollment (user_id, course_id);
CREATE INDEX ix_enrollment_offering_status ON enrollment (course_offering_id, status);

-- Why and until when a learner may access an enrollment's content. Append-mostly: revocation sets revoked_at.
CREATE TABLE access_grant (
    id UUID PRIMARY KEY,
    enrollment_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source VARCHAR(24) NOT NULL,
    source_ref_id UUID NULL,
    external_reference VARCHAR(128) NULL,
    note VARCHAR(500) NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    revoked_reason VARCHAR(32) NULL,
    revoked_by UUID NULL,
    granted_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_access_grant_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_access_grant_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_access_grant_source CHECK (source IN ('COHORT_ASSIGNMENT', 'CONTRACT', 'ADMIN_GRANT', 'PROMOTION')),
    -- Paid access must point at the offline payment it came from.
    CONSTRAINT ck_access_grant_contract_ref CHECK (source <> 'CONTRACT' OR external_reference IS NOT NULL),
    CONSTRAINT ck_access_grant_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_access_grant_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
-- A cohort placement (or other source record) yields at most one grant per enrollment: retries are no-ops.
CREATE UNIQUE INDEX uq_access_grant_source_ref ON access_grant (enrollment_id, source, source_ref_id)
    WHERE source_ref_id IS NOT NULL;
-- The authorization check: active grants of a user's enrollment.
CREATE INDEX ix_access_grant_active ON access_grant (user_id, enrollment_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_access_grant_source_ref ON access_grant (source_ref_id) WHERE source_ref_id IS NOT NULL;
CREATE INDEX ix_access_grant_reconciliation ON access_grant (source, created_at);
