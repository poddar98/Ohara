-- One learner question to lecture Q&A (docs 07 §7). The question itself is stored only as a hash.
CREATE TABLE ai_qa_log (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    enrollment_id UUID NOT NULL,
    organization_id UUID NULL,
    course_id UUID NOT NULL,
    question_sha256 CHAR(64) NOT NULL,
    retrieved_asset_ids UUID[] NOT NULL DEFAULT '{}',
    outcome VARCHAR(16) NOT NULL,
    model VARCHAR(64) NULL,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    grounded BOOLEAN NOT NULL DEFAULT false,
    citation_count SMALLINT NOT NULL DEFAULT 0,
    dropped_citation_count SMALLINT NOT NULL DEFAULT 0,
    feedback SMALLINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ai_qa_log_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_qa_log_outcome CHECK (outcome IN ('ANSWERED', 'UNSUPPORTED', 'NOT_COVERED', 'REFUSED', 'INVALID', 'UNAVAILABLE')),
    CONSTRAINT ck_ai_qa_log_feedback CHECK (feedback IS NULL OR feedback IN (-1, 1))
);
-- Per-user daily limit.
CREATE INDEX ix_ai_qa_log_user ON ai_qa_log (user_id, created_at);
-- Org usage and quality dashboards.
CREATE INDEX ix_ai_qa_log_org ON ai_qa_log (organization_id, created_at);
-- 90-day retention sweep.
CREATE INDEX ix_ai_qa_log_created ON ai_qa_log (created_at);
