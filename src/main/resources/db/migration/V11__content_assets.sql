-- A physical, reusable piece of content (video, PDF, image), independent of any curriculum.
CREATE TABLE content_asset (
    id UUID PRIMARY KEY,
    owner_organization_id UUID NULL,
    asset_type VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    media_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_UPLOAD',
    original_filename VARCHAR(255) NULL,
    mime_type VARCHAR(128) NULL,
    content_profile VARCHAR(24) NULL,
    source_object_key VARCHAR(512) NULL,
    source_version_id VARCHAR(128) NULL,
    source_sha256 CHAR(64) NULL,
    source_bytes BIGINT NULL,
    duration_ms BIGINT NULL,
    width INT NULL,
    height INT NULL,
    processing_generation INT NOT NULL DEFAULT 0,
    delivery_prefix VARCHAR(512) NULL,
    master_playlist_key VARCHAR(512) NULL,
    failure_code VARCHAR(64) NULL,
    failure_detail VARCHAR(1000) NULL,
    ready_at TIMESTAMPTZ NULL,
    retired_at TIMESTAMPTZ NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_content_asset_org FOREIGN KEY (owner_organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_content_asset_type CHECK (asset_type IN ('VIDEO', 'DOCUMENT', 'IMAGE')),
    CONSTRAINT ck_content_asset_status CHECK (media_status IN
        ('PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'REJECTED', 'CANCELLED', 'RETIRED')),
    CONSTRAINT ck_content_asset_profile CHECK (content_profile IS NULL OR content_profile IN ('TALKING_HEAD', 'SLIDES', 'SCREEN_DETAIL')),
    CONSTRAINT ck_content_asset_ready CHECK (media_status <> 'READY' OR ready_at IS NOT NULL)
);
-- Asset library listing per owner.
CREATE INDEX ix_content_asset_owner ON content_asset (owner_organization_id, asset_type, created_at DESC);
-- Sweepers and dashboards for work in flight.
CREATE INDEX ix_content_asset_in_flight ON content_asset (media_status) WHERE media_status IN ('UPLOADED', 'PROCESSING');

-- One upload attempt (a resumable multipart session) for an asset.
CREATE TABLE media_upload (
    id UUID PRIMARY KEY,
    content_asset_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'INITIATED',
    storage_upload_id VARCHAR(256) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    declared_bytes BIGINT NOT NULL,
    declared_mime VARCHAR(128) NOT NULL,
    declared_sha256 CHAR(64) NULL,
    part_size_bytes BIGINT NOT NULL,
    part_count INT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    initiated_by UUID NOT NULL,
    client_key VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_media_upload_asset FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_media_upload_status CHECK (status IN ('INITIATED', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_media_upload_numbers CHECK (declared_bytes > 0 AND part_size_bytes > 0 AND part_count BETWEEN 1 AND 10000),
    -- A retried "start upload" with the same client key returns the same upload.
    CONSTRAINT uq_media_upload_client_key UNIQUE (initiated_by, client_key)
);
CREATE UNIQUE INDEX uq_media_upload_open ON media_upload (content_asset_id) WHERE status = 'INITIATED';
-- Abandoned-upload sweeper.
CREATE INDEX ix_media_upload_expiring ON media_upload (expires_at) WHERE status = 'INITIATED';

-- Lessons can now reference assets (videos and documents).
ALTER TABLE lesson_content DROP CONSTRAINT ck_lesson_content_kind;
ALTER TABLE lesson_content ADD CONSTRAINT ck_lesson_content_kind CHECK (kind IN ('TEXT', 'EXTERNAL_LINK', 'VIDEO', 'DOCUMENT'));
ALTER TABLE lesson_content ADD CONSTRAINT ck_lesson_content_asset
    CHECK ((kind IN ('VIDEO', 'DOCUMENT')) = (content_asset_id IS NOT NULL));
-- An asset used by any lesson (draft or published) can't be deleted.
ALTER TABLE lesson_content ADD CONSTRAINT fk_lesson_content_asset
    FOREIGN KEY (content_asset_id) REFERENCES content_asset (id) ON DELETE RESTRICT;
