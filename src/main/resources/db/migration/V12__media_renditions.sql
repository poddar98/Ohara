-- Transcoding bookkeeping on the asset.
ALTER TABLE content_asset
    ADD COLUMN transcode_job_ref VARCHAR(128) NULL,
    ADD COLUMN transcode_attempts INT NOT NULL DEFAULT 0;

-- Verified outputs of one processing generation (HLS variants). Delivery paths include the generation, so a
-- reprocessed asset never overwrites what players are streaming.
CREATE TABLE media_rendition (
    id UUID PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    processing_generation INT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    height INT NOT NULL,
    width INT NOT NULL,
    bitrate_kbps INT NOT NULL,
    playlist_key VARCHAR(512) NOT NULL,
    segment_count INT NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_media_rendition_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_media_rendition_kind CHECK (kind IN ('HLS_VARIANT')),
    CONSTRAINT ck_media_rendition_numbers CHECK (height > 0 AND width > 0 AND bitrate_kbps > 0 AND segment_count > 0),
    CONSTRAINT uq_media_rendition UNIQUE (content_asset_id, processing_generation, kind, height)
);
CREATE INDEX ix_media_rendition_asset ON media_rendition (content_asset_id, processing_generation);
