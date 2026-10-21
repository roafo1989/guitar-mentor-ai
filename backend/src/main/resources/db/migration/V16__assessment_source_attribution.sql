-- #18 review R18-02: preserve verified assessment provenance without a public scoring API.
-- V15 may have been used on test branches; never rewrite already applied Flyway versions.
ALTER TABLE attempt_assessments
  ADD COLUMN source_principal VARCHAR(120) NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
  ADD CONSTRAINT chk_assessment_source_principal CHECK (length(trim(source_principal)) > 0);

-- Retain evidence provenance alongside method/version after source media expires.
CREATE OR REPLACE VIEW skill_evidence AS
SELECT a.id AS assessment_id,a.attempt_id,a.task_id,a.source_kind,
       a.method_id,a.method_version,a.created_at,
       r.criterion_code,s.skill_code,r.status,r.outcome,r.reason_code,r.evidence_text,
       CAST(NULL AS SMALLINT) AS confirmed_skill_level,
       a.source_principal AS source_principal
FROM attempt_assessments a
JOIN assessment_criterion_results r ON r.assessment_id=a.id AND r.task_id=a.task_id
JOIN task_criterion_snapshots s ON s.task_id=r.task_id AND s.criterion_code=r.criterion_code;
