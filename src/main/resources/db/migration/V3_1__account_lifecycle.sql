-- Self-registered accounts start unverified; accounts created before this migration are treated as verified.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ NULL;
UPDATE users SET email_verified_at = created_at WHERE status = 'ACTIVE';
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'PENDING_VERIFICATION';

-- Single-use, hashed tokens for email verification and password reset.
CREATE TABLE one_time_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_one_time_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_one_time_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_one_time_token_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

-- Reissuing a token invalidates the user's earlier unconsumed tokens of the same purpose.
CREATE INDEX ix_one_time_token_user_open ON one_time_token (user_id, purpose) WHERE consumed_at IS NULL;

-- Login throttling.
ALTER TABLE password_credential
    ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ NULL;
