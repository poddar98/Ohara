-- One speech-to-text run for one version of an asset's source (docs 07 §3). Tracks the provider job while it runs,
-- and failures, so status and spend are visible even when no transcript came out of it.
CREATE TABLE transcription (
    id UUID PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    provider_job_ref VARCHAR(256) NULL,
    attempts INT NOT NULL DEFAULT 0,
    language VARCHAR(16) NULL,
    audio_seconds INT NULL,
    cost_micro_usd BIGINT NULL,
    failure_code VARCHAR(64) NULL,
    failure_detail VARCHAR(1000) NULL,
    requested_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_transcription_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_transcription_status CHECK (status IN ('SUBMITTED', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_transcription_asset ON transcription (content_asset_id, created_at DESC);
-- At most one run in flight per asset.
CREATE UNIQUE INDEX uq_transcription_in_flight ON transcription (content_asset_id) WHERE status = 'SUBMITTED';

-- Generated outputs: transcripts and captions now; summaries, chapters, quiz drafts later.
CREATE TABLE lecture_artifact (
    id UUID PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    artifact_type VARCHAR(16) NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    transcript_version INT NULL,
    artifact_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    content JSONB NOT NULL,
    object_key VARCHAR(512) NULL,
    provider VARCHAR(32) NULL,
    model VARCHAR(64) NULL,
    prompt_template_version VARCHAR(32) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    audio_seconds INT NULL,
    cost_micro_usd BIGINT NULL,
    transcription_id UUID NULL,
    reviewed_by UUID NULL,
    reviewed_at TIMESTAMPTZ NULL,
    review_note VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_lecture_artifact_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT fk_lecture_artifact_transcription FOREIGN KEY (transcription_id) REFERENCES transcription (id) ON DELETE SET NULL,
    CONSTRAINT ck_lecture_artifact_type CHECK (artifact_type IN
        ('TRANSCRIPT', 'CAPTIONS', 'SUMMARY', 'CHAPTERS', 'QUIZ_DRAFT', 'FLASHCARDS', 'KEY_CONCEPTS')),
    CONSTRAINT ck_lecture_artifact_status CHECK (status IN ('GENERATED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT uq_lecture_artifact_version UNIQUE (content_asset_id, artifact_type, artifact_version)
);
-- Learners see the one approved artifact of each type.
CREATE UNIQUE INDEX uq_lecture_artifact_approved ON lecture_artifact (content_asset_id, artifact_type) WHERE status = 'APPROVED';

-- Timed transcript text; searchable with full-text search.
CREATE TABLE transcript_segment (
    id BIGSERIAL PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    transcript_version INT NOT NULL,
    seq INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    speaker VARCHAR(32) NULL,
    text TEXT NOT NULL,
    confidence NUMERIC(4, 3) NULL,
    tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    CONSTRAINT fk_transcript_segment_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT uq_transcript_segment UNIQUE (content_asset_id, transcript_version, seq),
    CONSTRAINT ck_transcript_segment_times CHECK (start_ms >= 0 AND end_ms >= start_ms)
);
CREATE INDEX ix_transcript_segment_tsv ON transcript_segment USING gin (tsv);
CREATE INDEX ix_transcript_segment_time ON transcript_segment (content_asset_id, transcript_version, start_ms);
