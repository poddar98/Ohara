-- Account deletion (docs 04 §3.10): requested → DEACTIVATED, anonymized after a grace period unless on legal hold.
ALTER TABLE users ADD COLUMN deletion_requested_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD COLUMN deletion_due_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD COLUMN legal_hold_reason VARCHAR(300) NULL;
ALTER TABLE users ADD COLUMN anonymized_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_anonymized CHECK (status <> 'ANONYMIZED' OR anonymized_at IS NOT NULL);
-- Deletions waiting to run (support's view).
CREATE INDEX ix_users_deletion_due ON users (deletion_due_at) WHERE deletion_due_at IS NOT NULL AND status = 'DEACTIVATED';
