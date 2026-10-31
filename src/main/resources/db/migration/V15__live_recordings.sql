-- Recordings of live classes become ordinary video assets.
ALTER TABLE content_asset ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'UPLOAD';
ALTER TABLE content_asset ADD COLUMN live_session_id UUID NULL;
ALTER TABLE content_asset ADD CONSTRAINT ck_content_asset_origin CHECK (origin IN ('UPLOAD', 'LIVE_RECORDING'));
ALTER TABLE content_asset ADD CONSTRAINT ck_content_asset_recording
    CHECK ((origin = 'LIVE_RECORDING') = (live_session_id IS NOT NULL));
-- One recording asset per session: registration is idempotent.
CREATE UNIQUE INDEX uq_content_asset_live_session ON content_asset (live_session_id) WHERE live_session_id IS NOT NULL;

-- Where the provider wrote the recording (HLS master playlist key), reported by its "recording ended" event.
ALTER TABLE live_session ADD COLUMN recording_master_key VARCHAR(512) NULL;
-- Reverse lookup when the recording asset changes state.
CREATE INDEX ix_live_session_recording ON live_session (recording_asset_id) WHERE recording_asset_id IS NOT NULL;
