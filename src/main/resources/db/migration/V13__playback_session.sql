-- One authorization to stream one video. Refreshing re-checks access, so revocation takes effect at the next refresh.
CREATE TABLE playback_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    lesson_id UUID NOT NULL,
    content_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    enrollment_id UUID NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    refresh_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    refreshed_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_playback_session_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_playback_session_asset FOREIGN KEY (asset_id) REFERENCES content_asset (id) ON DELETE CASCADE
);
CREATE INDEX ix_playback_session_user ON playback_session (user_id, created_at DESC);
