-- Needed to forbid overlapping sessions for the same host with an exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- One provider channel (and chat room) per cohort, reused across that cohort's sessions.
CREATE TABLE live_channel (
    id UUID PRIMARY KEY,
    cohort_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    channel_ref VARCHAR(256) NOT NULL,
    ingest_endpoint VARCHAR(256) NOT NULL,
    chat_room_ref VARCHAR(256) NOT NULL,
    hd BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_live_channel_cohort FOREIGN KEY (cohort_id) REFERENCES cohort (id) ON DELETE RESTRICT,
    CONSTRAINT uq_live_channel_cohort UNIQUE (cohort_id),
    CONSTRAINT uq_live_channel_ref UNIQUE (provider, channel_ref)
);

CREATE TABLE live_session (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    cohort_id UUID NOT NULL,
    course_offering_id UUID NOT NULL,
    lesson_id UUID NULL,
    channel_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    host_user_id UUID NOT NULL,
    scheduled_start TIMESTAMPTZ NOT NULL,
    scheduled_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
    needs_hd BOOLEAN NOT NULL DEFAULT false,
    actual_start TIMESTAMPTZ NULL,
    actual_end TIMESTAMPTZ NULL,
    -- Set when the encoder disconnects mid-session; the session ends if it stays offline past the reconnect grace.
    stream_offline_since TIMESTAMPTZ NULL,
    peak_concurrent_viewers INT NULL,
    recording_asset_id UUID NULL,
    cancelled_reason VARCHAR(200) NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_live_session_cohort FOREIGN KEY (cohort_id, organization_id) REFERENCES cohort (id, organization_id),
    CONSTRAINT fk_live_session_offering FOREIGN KEY (course_offering_id) REFERENCES course_offering (id) ON DELETE RESTRICT,
    CONSTRAINT fk_live_session_channel FOREIGN KEY (channel_id) REFERENCES live_channel (id) ON DELETE RESTRICT,
    CONSTRAINT fk_live_session_host FOREIGN KEY (host_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_live_session_recording FOREIGN KEY (recording_asset_id) REFERENCES content_asset (id) ON DELETE SET NULL,
    CONSTRAINT ck_live_session_window CHECK (scheduled_end > scheduled_start),
    CONSTRAINT ck_live_session_status CHECK (status IN ('SCHEDULED', 'READY', 'LIVE', 'ENDED', 'CANCELLED',
        'RECORDING_PROCESSING', 'RECORDING_READY', 'RECORDING_FAILED')),
    -- A host can't be double-booked (cancelled sessions don't count).
    CONSTRAINT ex_live_session_host_overlap EXCLUDE USING gist (
        host_user_id WITH =, tstzrange(scheduled_start, scheduled_end) WITH &&) WHERE (status <> 'CANCELLED'),
    -- Nor can a cohort's channel carry two sessions at once.
    CONSTRAINT ex_live_session_channel_overlap EXCLUDE USING gist (
        channel_id WITH =, tstzrange(scheduled_start, scheduled_end) WITH &&) WHERE (status <> 'CANCELLED')
);
CREATE INDEX ix_live_session_cohort_start ON live_session (cohort_id, scheduled_start);
CREATE INDEX ix_live_session_org_start ON live_session (organization_id, scheduled_start);
CREATE INDEX ix_live_session_offering ON live_session (course_offering_id, scheduled_start);
CREATE INDEX ix_live_session_channel_status ON live_session (channel_id, status);
-- Sweeper for sessions left live.
CREATE INDEX ix_live_session_live ON live_session (status) WHERE status = 'LIVE';

-- Aggregated attendance (never raw player events). Status is set when the session ends.
CREATE TABLE live_attendance (
    live_session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    first_joined_at TIMESTAMPTZ NULL,
    last_seen_at TIMESTAMPTZ NULL,
    join_count INT NOT NULL DEFAULT 0,
    attended_seconds INT NOT NULL DEFAULT 0,
    attendance_status VARCHAR(16) NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'PLAYER_HEARTBEAT',
    note VARCHAR(300) NULL,
    CONSTRAINT pk_live_attendance PRIMARY KEY (live_session_id, user_id),
    CONSTRAINT fk_live_attendance_session FOREIGN KEY (live_session_id) REFERENCES live_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_live_attendance_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_live_attendance_status CHECK (attendance_status IS NULL OR attendance_status IN ('PRESENT', 'PARTIAL', 'ABSENT')),
    CONSTRAINT ck_live_attendance_source CHECK (source IN ('PLAYER_HEARTBEAT', 'MANUAL')),
    CONSTRAINT ck_live_attendance_seconds CHECK (attended_seconds >= 0)
);
