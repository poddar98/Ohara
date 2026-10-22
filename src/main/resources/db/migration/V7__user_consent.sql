-- Append-only consent history: every grant or withdrawal is a new row; the latest row per type is the current state.
CREATE TABLE user_consent (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    consent_type VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    granted BOOLEAN NOT NULL,
    source VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_consent_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_consent_type CHECK (consent_type IN ('TERMS', 'PRIVACY', 'MARKETING_EMAIL')),
    CONSTRAINT ck_user_consent_source CHECK (source IN ('SIGNUP', 'INVITATION', 'SETTINGS'))
);
CREATE INDEX ix_user_consent_user_type ON user_consent (user_id, consent_type, recorded_at DESC);
