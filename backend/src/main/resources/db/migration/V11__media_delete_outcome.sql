-- Preserve original TTL reason; capture the outcome of the filesystem operation separately.
ALTER TABLE submission_attempts ADD COLUMN media_delete_outcome VARCHAR(32);
-- Existing records with legacy overwritten reasons cannot reliably reconstruct original TTL source.
UPDATE submission_attempts SET media_delete_outcome='ALREADY_ABSENT'
 WHERE media_state='DELETED' AND media_delete_reason='MISSING_FILE';
