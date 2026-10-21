-- #18: pedagogical history is separate from technical media processing.
-- Additive only. Existing diagnostic_tasks and submission_attempts are not renumbered.
CREATE TABLE task_learning_state (
  task_id UUID PRIMARY KEY REFERENCES diagnostic_tasks(id) ON DELETE CASCADE,
  state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
    CHECK (state IN ('ACTIVE','COMPLETED_PASSED','COMPLETED_BY_LEARNER')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  CONSTRAINT chk_learning_terminal_time CHECK
    ((state = 'ACTIVE' AND completed_at IS NULL)
     OR (state <> 'ACTIVE' AND completed_at IS NOT NULL))
);
INSERT INTO task_learning_state(task_id,state,version,updated_at)
SELECT id,'ACTIVE',0,now() FROM diagnostic_tasks;

CREATE TABLE task_criterion_snapshots (
  task_id UUID NOT NULL REFERENCES diagnostic_tasks(id) ON DELETE CASCADE,
  criterion_code VARCHAR(60) NOT NULL,
  skill_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
  label VARCHAR(160) NOT NULL,
  observable_result TEXT NOT NULL,
  assessment_policy VARCHAR(40) NOT NULL
    CHECK (assessment_policy IN ('MANUAL_OR_NOT_ASSESSED','VALIDATED_MEASUREMENT')),
  display_order INTEGER NOT NULL,
  captured_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(task_id,criterion_code)
);
INSERT INTO task_criterion_snapshots
  (task_id,criterion_code,skill_code,label,observable_result,assessment_policy,display_order,captured_at)
SELECT t.id,c.criterion_code,c.skill_code,c.label,c.observable_result,
       c.assessment_policy,c.display_order,now()
FROM diagnostic_tasks t JOIN exercise_criteria c ON c.exercise_id=t.exercise_id;

-- These composite keys ensure that an assessment or decision cannot refer to
-- another task's attempt, even if the application makes a mistake.
ALTER TABLE submission_attempts ADD CONSTRAINT uq_attempt_id_task UNIQUE (id,diagnostic_task_id);

CREATE TABLE attempt_assessments (
  id UUID PRIMARY KEY,
  task_id UUID NOT NULL,
  attempt_id UUID NOT NULL,
  revision INTEGER NOT NULL CHECK (revision > 0),
  source_operation_id UUID NOT NULL,
  input_fingerprint CHAR(64) NOT NULL,
  method_id VARCHAR(100) NOT NULL,
  method_version VARCHAR(60) NOT NULL,
  source_kind VARCHAR(32) NOT NULL CHECK (source_kind IN ('HUMAN_REVIEW','VALIDATED_MEASUREMENT')),
  recommendation VARCHAR(16) NOT NULL CHECK (recommendation IN ('PASS','REVISE','UNCERTAIN')),
  created_at TIMESTAMPTZ NOT NULL,
  FOREIGN KEY(attempt_id,task_id) REFERENCES submission_attempts(id,diagnostic_task_id),
  UNIQUE(attempt_id,revision),
  UNIQUE(attempt_id,source_operation_id),
  UNIQUE(id,task_id),
  UNIQUE(id,attempt_id,task_id)
);
CREATE INDEX ix_attempt_assessments_revision ON attempt_assessments(attempt_id,revision DESC);

CREATE TABLE assessment_criterion_results (
  assessment_id UUID NOT NULL,
  task_id UUID NOT NULL,
  criterion_code VARCHAR(60) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('ASSESSED','UNCERTAIN','NOT_ASSESSED')),
  outcome VARCHAR(16) CHECK (outcome IN ('MEETS','NEEDS_WORK')),
  reason_code VARCHAR(80),
  evidence_text VARCHAR(2000),
  PRIMARY KEY(assessment_id,criterion_code),
  FOREIGN KEY(assessment_id,task_id) REFERENCES attempt_assessments(id,task_id),
  FOREIGN KEY(task_id,criterion_code) REFERENCES task_criterion_snapshots(task_id,criterion_code),
  CONSTRAINT chk_criterion_result CHECK
    ((status='ASSESSED' AND outcome IS NOT NULL)
     OR (status<>'ASSESSED' AND outcome IS NULL AND reason_code IS NOT NULL))
);

CREATE TABLE learner_decisions (
  id UUID PRIMARY KEY,
  task_id UUID NOT NULL REFERENCES task_learning_state(task_id),
  attempt_id UUID NOT NULL,
  assessment_id UUID,
  request_id UUID NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  action VARCHAR(24) NOT NULL CHECK (action IN ('COMPLETE','CONTINUE_PRACTICING')),
  resulting_state VARCHAR(32) NOT NULL
    CHECK (resulting_state IN ('ACTIVE','COMPLETED_PASSED','COMPLETED_BY_LEARNER')),
  resulting_version BIGINT NOT NULL CHECK (resulting_version > 0),
  reason_code VARCHAR(60) NOT NULL,
  reason_text VARCHAR(2000),
  created_at TIMESTAMPTZ NOT NULL,
  FOREIGN KEY(attempt_id,task_id) REFERENCES submission_attempts(id,diagnostic_task_id),
  FOREIGN KEY(assessment_id,attempt_id,task_id)
    REFERENCES attempt_assessments(id,attempt_id,task_id),
  UNIQUE(task_id,request_id)
);
CREATE INDEX ix_learner_decisions_time ON learner_decisions(task_id,created_at DESC,id DESC);

-- Read-only evidence projection: criterion provenance, never a fabricated skill level.
CREATE VIEW skill_evidence AS
SELECT a.id AS assessment_id,a.attempt_id,a.task_id,a.source_kind,
       a.method_id,a.method_version,a.created_at,
       r.criterion_code,s.skill_code,r.status,r.outcome,r.reason_code,r.evidence_text,
       CAST(NULL AS SMALLINT) AS confirmed_skill_level
FROM attempt_assessments a
JOIN assessment_criterion_results r ON r.assessment_id=a.id AND r.task_id=a.task_id
JOIN task_criterion_snapshots s ON s.task_id=r.task_id AND s.criterion_code=r.criterion_code;
