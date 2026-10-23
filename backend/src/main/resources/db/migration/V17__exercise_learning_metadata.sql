-- #119: optional, version-specific authored metadata; no content backfill.
ALTER TABLE exercises
  ADD COLUMN learning_goal TEXT NULL,
  ADD COLUMN practice_instructions TEXT NULL,
  ADD COLUMN tuning VARCHAR(64) NULL,
  ADD CONSTRAINT chk_exercise_learning_goal
    CHECK (learning_goal IS NULL OR
      (char_length(learning_goal) <= 4000 AND learning_goal ~ '[^[:space:]]')),
  ADD CONSTRAINT chk_exercise_practice_instructions
    CHECK (practice_instructions IS NULL OR
      (char_length(practice_instructions) <= 8000 AND practice_instructions ~ '[^[:space:]]')),
  ADD CONSTRAINT chk_exercise_tuning
    CHECK (tuning IS NULL OR tuning ~ '[^[:space:]]');
