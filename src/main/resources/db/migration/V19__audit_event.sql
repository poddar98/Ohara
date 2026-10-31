-- Security-relevant actions (docs 03 §10.4, 10 §"Audited actions"). Append-only: no updates, no deletes.
CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id UUID NULL,
    actor_type VARCHAR(8) NOT NULL,
    organization_id UUID NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NULL,
    ip_hash CHAR(32) NULL,
    user_agent VARCHAR(256) NULL,
    details JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT ck_audit_event_actor CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_audit_event_user_actor CHECK (actor_type = 'SYSTEM' OR actor_user_id IS NOT NULL)
);
CREATE INDEX ix_audit_event_org ON audit_event (organization_id, occurred_at DESC);
CREATE INDEX ix_audit_event_resource ON audit_event (resource_type, resource_id);
CREATE INDEX ix_audit_event_actor ON audit_event (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_event_action ON audit_event (action, occurred_at DESC);

CREATE FUNCTION ohara_reject_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION ohara_reject_audit_change();
-- TRUNCATE bypasses row triggers; block it too.
CREATE TRIGGER trg_audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION ohara_reject_audit_change();
