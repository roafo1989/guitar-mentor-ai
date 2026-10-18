-- V7 is immutable: keep migration changes separate so current student attempts survive.
-- PROCESSED denotes a successful TECHNICAL file integrity check, not AI grading.
ALTER TABLE submission_attempts
  DROP CONSTRAINT IF EXISTS submission_attempts_status_check;
UPDATE submission_attempts SET status = 'UPLOADED' WHERE status = 'RECEIVED_UNASSESSED';
ALTER TABLE submission_attempts
  ADD CONSTRAINT submission_attempts_status_check
  CHECK (status IN ('UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED'));
ALTER TABLE submission_attempts
  ADD COLUMN processing_started_at TIMESTAMPTZ,
  ADD COLUMN processing_finished_at TIMESTAMPTZ,
  ADD COLUMN technical_sha256 VARCHAR(64),
  ADD COLUMN processing_error_code VARCHAR(50);
CREATE INDEX ix_attempts_processing ON submission_attempts(status, submitted_at);
