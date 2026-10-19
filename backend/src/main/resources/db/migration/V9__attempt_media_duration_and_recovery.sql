-- Server-side media duration and bounded recovery of interrupted technical checks.
ALTER TABLE submission_attempts
    ADD COLUMN audio_duration_seconds NUMERIC(9,3),
    ADD COLUMN processing_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (processing_attempts >= 0);
