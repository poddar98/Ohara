-- One LLM generation run (summary+chapters, quiz draft, flashcards) from one transcript version (docs 07 §3).
CREATE TABLE ai_generation (
    id UUID PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    task VARCHAR(24) NOT NULL,
    transcript_version INT NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NULL,
    attempts INT NOT NULL DEFAULT 0,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cache_read_tokens INT NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    instructions VARCHAR(500) NULL,
    failure_code VARCHAR(64) NULL,
    failure_detail VARCHAR(1000) NULL,
    requested_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_ai_generation_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_generation_task CHECK (task IN ('SUMMARY_CHAPTERS', 'QUIZ_DRAFT', 'FLASHCARDS')),
    CONSTRAINT ck_ai_generation_status CHECK (status IN ('SUBMITTED', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_ai_generation_asset ON ai_generation (content_asset_id, task, created_at DESC);
-- One run of a task in flight per asset.
CREATE UNIQUE INDEX uq_ai_generation_in_flight ON ai_generation (content_asset_id, task) WHERE status = 'SUBMITTED';

ALTER TABLE lecture_artifact ADD COLUMN generation_id UUID NULL;
ALTER TABLE lecture_artifact ADD CONSTRAINT fk_lecture_artifact_generation
    FOREIGN KEY (generation_id) REFERENCES ai_generation (id) ON DELETE SET NULL;
ALTER TABLE lecture_artifact ADD COLUMN edited_by UUID NULL;
ALTER TABLE lecture_artifact ADD COLUMN edited_at TIMESTAMPTZ NULL;
