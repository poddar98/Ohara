-- Timestamps: store instants, not wall-clock values. Existing rows were written in UTC (hibernate.jdbc.time_zone=UTC).
ALTER TABLE app_metadata
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE users
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
    ALTER COLUMN last_login_at TYPE TIMESTAMPTZ USING last_login_at AT TIME ZONE 'UTC';

ALTER TABLE auth_identity
    ALTER COLUMN verified_at TYPE TIMESTAMPTZ USING verified_at AT TIME ZONE 'UTC';

ALTER TABLE password_credential
    ALTER COLUMN password_changed_at TYPE TIMESTAMPTZ USING password_changed_at AT TIME ZONE 'UTC';

ALTER TABLE refresh_token
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ USING expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN revoked_at TYPE TIMESTAMPTZ USING revoked_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';

-- Account lifecycle statuses (INACTIVE is replaced by DEACTIVATED).
UPDATE users SET status = 'DEACTIVATED' WHERE status = 'INACTIVE';
ALTER TABLE users
    ADD CONSTRAINT ck_users_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'ANONYMIZED'));

-- Bumped whenever all existing access tokens must stop working (session reuse, password reset, suspension, ...).
ALTER TABLE users ADD COLUMN auth_version INT NOT NULL DEFAULT 0;

-- Email uniqueness is case-insensitive.
ALTER TABLE users DROP CONSTRAINT users_email_key;
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- History must survive account removal: users are anonymized, never deleted, so block cascading deletes.
ALTER TABLE user_profile DROP CONSTRAINT fk_user_profile_user,
    ADD CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE user_role DROP CONSTRAINT fk_user_role_user,
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE auth_identity DROP CONSTRAINT fk_auth_identity_user,
    ADD CONSTRAINT fk_auth_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE password_credential DROP CONSTRAINT fk_password_credential_auth_identity,
    ADD CONSTRAINT fk_password_credential_auth_identity
        FOREIGN KEY (auth_identity_id) REFERENCES auth_identity (id) ON DELETE RESTRICT;
ALTER TABLE refresh_token DROP CONSTRAINT fk_refresh_token_user,
    ADD CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;

-- Serves reuse detection (revoke every active session of a user).
CREATE INDEX ix_refresh_token_user_active ON refresh_token (user_id) WHERE revoked_at IS NULL;
