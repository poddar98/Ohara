-- Progress per enrollment and lesson identity (lesson_key), so it survives moving to a newer course version.
CREATE TABLE lesson_progress (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    enrollment_id UUID NOT NULL,
    lesson_key UUID NOT NULL,
    lesson_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    video_position_ms BIGINT NULL,
    video_duration_ms BIGINT NULL,
    watched_ms BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ NULL,
    client_updated_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_lesson_progress_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollment (id) ON DELETE RESTRICT,
    CONSTRAINT uq_lesson_progress UNIQUE (enrollment_id, lesson_key),
    CONSTRAINT ck_lesson_progress_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_lesson_progress_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_lesson_progress_numbers CHECK (watched_ms >= 0 AND (video_position_ms IS NULL OR video_position_ms >= 0)
        AND (video_duration_ms IS NULL OR video_duration_ms > 0))
);
-- "Continue watching": the learner's most recently touched lessons.
CREATE INDEX ix_lesson_progress_user_recent ON lesson_progress (user_id, updated_at DESC);
