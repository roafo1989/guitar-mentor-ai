-- #216: preserve historic tasks, archive source session atomically, and record durable replay.
ALTER TABLE diagnostic_sessions DROP CONSTRAINT IF EXISTS diagnostic_sessions_status_check;
ALTER TABLE diagnostic_sessions ADD CONSTRAINT diagnostic_sessions_status_check
 CHECK (status IN ('COLLECTING_EVIDENCE','READY_FOR_TASK','TASK_ASSIGNED','ARCHIVED'));
CREATE TABLE lesson_transitions (
 id UUID PRIMARY KEY,
 learner_id UUID NOT NULL REFERENCES learners(id),
 instrument_profile_id UUID NOT NULL REFERENCES instrument_profiles(id),
 request_id UUID NOT NULL,
 source_task_id UUID NOT NULL UNIQUE REFERENCES diagnostic_tasks(id),
 target_task_id UUID NOT NULL UNIQUE REFERENCES diagnostic_tasks(id),
 requested_exercise_code VARCHAR(50) NOT NULL,
 requested_exercise_version VARCHAR(20) NOT NULL,
 expected_source_version BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(learner_id, request_id),
 CHECK (expected_source_version >= 0)
);
CREATE INDEX ix_lesson_transitions_learner ON lesson_transitions(learner_id,created_at DESC);
CREATE INDEX ix_lesson_history ON diagnostic_tasks(assigned_at DESC,id DESC);
