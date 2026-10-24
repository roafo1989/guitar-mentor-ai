-- #145: backwards-compatible separation of technical diagnostic probes and optional repertoire.
-- Existing versioned assignments retain their exercise UUID and original media.
ALTER TABLE exercises
  ADD COLUMN content_kind VARCHAR(32) NOT NULL DEFAULT 'LEARNING_REPERTOIRE',
  ADD COLUMN tempo_unit VARCHAR(24) NOT NULL DEFAULT 'quarter',
  ADD COLUMN duration_target_seconds INT NULL,
  ADD CONSTRAINT ck_exercises_content_kind CHECK
    (content_kind IN ('DIAGNOSTIC_PROBE', 'LEARNING_REPERTOIRE')),
  ADD CONSTRAINT ck_exercises_tempo_unit CHECK
    (tempo_unit IN ('quarter', 'eighth', 'dotted-quarter')),
  ADD CONSTRAINT ck_exercises_duration CHECK
    (duration_target_seconds IS NULL OR duration_target_seconds BETWEEN 5 AND 600);
-- No new item becomes current or assignable through this metadata migration.
-- Existing GM-EB01 stays LEARNING_REPERTOIRE; no destructive backfill.
