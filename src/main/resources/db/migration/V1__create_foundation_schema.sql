CREATE TABLE IF NOT EXISTS app_metadata (
    id BIGSERIAL PRIMARY KEY,
    key_name VARCHAR(255) NOT NULL UNIQUE,
    value_text VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_app_metadata_key_name
    ON app_metadata (key_name);
