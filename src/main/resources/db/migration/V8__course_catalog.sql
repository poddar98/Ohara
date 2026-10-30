-- Learning products. A course is the stable identity; its curriculum lives in versions.
CREATE TABLE course (
    id UUID PRIMARY KEY,
    owner_organization_id UUID NULL,
    slug VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    current_published_version_id UUID NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_course_owner_org FOREIGN KEY (owner_organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_slug CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$'),
    CONSTRAINT ck_course_visibility CHECK (visibility IN ('PUBLIC', 'ORG_PRIVATE', 'UNLISTED')),
    CONSTRAINT ck_course_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_course_private_needs_org CHECK (visibility <> 'ORG_PRIVATE' OR owner_organization_id IS NOT NULL)
);
-- Slugs are unique in the platform catalog and within each organization.
CREATE UNIQUE INDEX uq_course_slug_platform ON course (slug) WHERE owner_organization_id IS NULL;
CREATE UNIQUE INDEX uq_course_slug_org ON course (owner_organization_id, slug) WHERE owner_organization_id IS NOT NULL;
CREATE INDEX ix_course_status_visibility ON course (status, visibility);

CREATE TABLE course_instructor (
    course_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_course_instructor PRIMARY KEY (course_id, user_id),
    CONSTRAINT fk_course_instructor_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_instructor_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_instructor_role CHECK (role IN ('OWNER', 'INSTRUCTOR', 'TA'))
);
CREATE INDEX ix_course_instructor_user ON course_instructor (user_id);

-- A snapshot of the curriculum. DRAFT is editable; PUBLISHED and ARCHIVED are frozen (enforced by triggers below).
CREATE TABLE course_version (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL,
    version_number INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(300) NULL,
    description TEXT NULL,
    language VARCHAR(16) NOT NULL DEFAULT 'en',
    level VARCHAR(16) NULL,
    estimated_minutes INT NULL,
    based_on_version_id UUID NULL,
    change_notes TEXT NULL,
    published_at TIMESTAMPTZ NULL,
    published_by UUID NULL,
    archived_at TIMESTAMPTZ NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_course_version_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_version_based_on FOREIGN KEY (based_on_version_id) REFERENCES course_version (id),
    CONSTRAINT uq_course_version_number UNIQUE (course_id, version_number),
    CONSTRAINT ck_course_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_course_version_level CHECK (level IS NULL OR level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT ck_course_version_minutes CHECK (estimated_minutes IS NULL OR estimated_minutes >= 0)
);
-- At most one draft per course.
CREATE UNIQUE INDEX uq_course_version_draft ON course_version (course_id) WHERE status = 'DRAFT';
CREATE INDEX ix_course_version_course_status ON course_version (course_id, status);

ALTER TABLE course ADD CONSTRAINT fk_course_current_version
    FOREIGN KEY (current_published_version_id) REFERENCES course_version (id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE course_module (
    id UUID PRIMARY KEY,
    course_version_id UUID NOT NULL,
    module_key UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    position INT NOT NULL,
    CONSTRAINT fk_course_module_version FOREIGN KEY (course_version_id) REFERENCES course_version (id) ON DELETE CASCADE,
    CONSTRAINT uq_course_module_position UNIQUE (course_version_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_course_module_key UNIQUE (course_version_id, module_key),
    CONSTRAINT ck_course_module_position CHECK (position >= 0)
);

CREATE TABLE lesson (
    id UUID PRIMARY KEY,
    course_module_id UUID NOT NULL,
    course_version_id UUID NOT NULL,
    lesson_key UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    type VARCHAR(16) NOT NULL,
    position INT NOT NULL,
    is_preview BOOLEAN NOT NULL DEFAULT false,
    duration_seconds INT NULL,
    CONSTRAINT fk_lesson_module FOREIGN KEY (course_module_id) REFERENCES course_module (id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_version FOREIGN KEY (course_version_id) REFERENCES course_version (id) ON DELETE CASCADE,
    CONSTRAINT uq_lesson_position UNIQUE (course_module_id, position) DEFERRABLE INITIALLY DEFERRED,
    -- Stable identity across versions: progress is keyed by lesson_key.
    CONSTRAINT uq_lesson_key UNIQUE (course_version_id, lesson_key),
    CONSTRAINT ck_lesson_type CHECK (type IN ('VIDEO', 'READING', 'QUIZ', 'ASSIGNMENT', 'LIVE', 'MIXED')),
    CONSTRAINT ck_lesson_position CHECK (position >= 0),
    CONSTRAINT ck_lesson_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);
CREATE INDEX ix_lesson_version ON lesson (course_version_id);
CREATE INDEX ix_lesson_key ON lesson (lesson_key);

-- What a lesson contains. Asset-backed kinds (VIDEO, DOCUMENT, ...) arrive with content assets (R3);
-- content_asset_id gets its foreign key then.
CREATE TABLE lesson_content (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL,
    course_version_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(200) NULL,
    body TEXT NULL,
    external_url VARCHAR(2048) NULL,
    content_asset_id UUID NULL,
    position INT NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT fk_lesson_content_lesson FOREIGN KEY (lesson_id) REFERENCES lesson (id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_content_version FOREIGN KEY (course_version_id) REFERENCES course_version (id) ON DELETE CASCADE,
    CONSTRAINT uq_lesson_content_position UNIQUE (lesson_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_lesson_content_kind CHECK (kind IN ('TEXT', 'EXTERNAL_LINK')),
    CONSTRAINT ck_lesson_content_text CHECK (kind <> 'TEXT' OR body IS NOT NULL),
    CONSTRAINT ck_lesson_content_link CHECK ((kind = 'EXTERNAL_LINK') = (external_url IS NOT NULL)),
    CONSTRAINT ck_lesson_content_position CHECK (position >= 0)
);
CREATE INDEX ix_lesson_content_lesson ON lesson_content (lesson_id);
CREATE INDEX ix_lesson_content_asset ON lesson_content (content_asset_id) WHERE content_asset_id IS NOT NULL;

-- Defence in depth: curriculum rows of a published or archived version can't change, whatever the application does.
CREATE FUNCTION ohara_reject_frozen_curriculum() RETURNS trigger AS $$
DECLARE
    version_id UUID := CASE WHEN TG_OP = 'DELETE' THEN OLD.course_version_id ELSE NEW.course_version_id END;
    version_status VARCHAR(16);
BEGIN
    SELECT status INTO version_status FROM course_version WHERE id = version_id;
    -- A version being deleted (cascade) has no row left to check; drafts are the only deletable versions.
    IF version_status IS NOT NULL AND version_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'course version % is % and cannot be modified', version_id, version_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_course_module_frozen BEFORE INSERT OR UPDATE OR DELETE ON course_module
    FOR EACH ROW EXECUTE FUNCTION ohara_reject_frozen_curriculum();
CREATE TRIGGER trg_lesson_frozen BEFORE INSERT OR UPDATE OR DELETE ON lesson
    FOR EACH ROW EXECUTE FUNCTION ohara_reject_frozen_curriculum();
CREATE TRIGGER trg_lesson_content_frozen BEFORE INSERT OR UPDATE OR DELETE ON lesson_content
    FOR EACH ROW EXECUTE FUNCTION ohara_reject_frozen_curriculum();

-- A published version may only move to ARCHIVED; its content fields never change. Published versions can't be deleted.
CREATE FUNCTION ohara_reject_frozen_version() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'course version % is % and cannot be deleted', OLD.id, OLD.status USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status <> 'DRAFT' AND (
        NEW.title IS DISTINCT FROM OLD.title OR NEW.subtitle IS DISTINCT FROM OLD.subtitle
        OR NEW.description IS DISTINCT FROM OLD.description OR NEW.language IS DISTINCT FROM OLD.language
        OR NEW.level IS DISTINCT FROM OLD.level OR NEW.estimated_minutes IS DISTINCT FROM OLD.estimated_minutes
        OR NEW.version_number IS DISTINCT FROM OLD.version_number OR NEW.course_id IS DISTINCT FROM OLD.course_id
        OR NEW.published_at IS DISTINCT FROM OLD.published_at
        OR (OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED')
        OR (OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'ARCHIVED'))) THEN
        RAISE EXCEPTION 'course version % is % and cannot be modified', OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_course_version_frozen BEFORE UPDATE OR DELETE ON course_version
    FOR EACH ROW EXECUTE FUNCTION ohara_reject_frozen_version();
