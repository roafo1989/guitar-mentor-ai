-- Preserve all historical attempts; do not fabricate finalization dates for unknown states.
CREATE TABLE system_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO system_settings(setting_key,setting_value) VALUES
 ('media.retention_after_processing_hours','48'),
 ('media.max_unprocessed_age_hours','48'),
 ('media.cleanup_interval_minutes','60');

ALTER TABLE submission_attempts
 ADD COLUMN processing_plan VARCHAR(64) NOT NULL DEFAULT 'TECHNICAL_ONLY_V1',
 ADD COLUMN processing_finalized_at TIMESTAMPTZ,
 ADD COLUMN media_state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
 ADD COLUMN media_deleted_at TIMESTAMPTZ,
 ADD COLUMN media_delete_attempts INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN media_delete_error_code VARCHAR(50),
 ADD COLUMN media_delete_reason VARCHAR(32),
 ADD CONSTRAINT submission_media_state_ck CHECK (media_state IN ('AVAILABLE','DELETE_PENDING','DELETED')),
 ADD CONSTRAINT submission_delete_attempts_ck CHECK (media_delete_attempts >= 0);
UPDATE submission_attempts SET processing_finalized_at=processing_finished_at
 WHERE status IN ('PROCESSED','FAILED') AND processing_finished_at IS NOT NULL;
CREATE INDEX submission_media_cleanup_idx ON submission_attempts(media_state,processing_finalized_at,submitted_at);
