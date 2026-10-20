-- FT-002-02 (#16): additive skill graph, exercise criteria, and self-reported history.
-- Follows V10/V11 media retention already merged from #21. Never edit previous migrations.
ALTER TABLE skill_codes
  ADD COLUMN description TEXT NOT NULL DEFAULT '',
  ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'TECHNIQUE',
  ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE skill_codes AS s SET
  description = v.description,
  display_order = v.display_order,
  category = v.category
FROM (VALUES
 ('PULSE', 'Удержание ровных долей', 10, 'RHYTHM'),
 ('SUBDIVISION', 'Четверти, восьмые и паузы', 20, 'RHYTHM'),
 ('RH_HAND', 'Звукоизвлечение пальцами', 30, 'TECHNIQUE'),
 ('BASS_PULSE', 'Ритмичный бас большим пальцем', 40, 'TECHNIQUE'),
 ('MELODY', 'Разборчивое ведение мелодии', 50, 'MUSICALITY'),
 ('VOICE_INDEPENDENCE', 'Одновременное движение баса и мелодии', 60, 'TECHNIQUE'),
 ('LEFT_HAND', 'Чистота прижатия и переходов', 70, 'TECHNIQUE'),
 ('DYNAMICS', 'Акценты и динамика двух голосов', 80, 'MUSICALITY'),
 ('TAB_READING', 'Чтение табулатуры и длительностей', 90, 'THEORY')
) AS v(code, description, display_order, category)
WHERE s.code = v.code;

CREATE TABLE skill_prerequisites (
 skill_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
 prerequisite_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
 PRIMARY KEY (skill_code, prerequisite_code),
 CONSTRAINT chk_skill_no_self_prerequisite CHECK (skill_code <> prerequisite_code)
);
INSERT INTO skill_prerequisites(skill_code, prerequisite_code) VALUES
 ('SUBDIVISION','PULSE'), ('BASS_PULSE','PULSE'), ('BASS_PULSE','RH_HAND'),
 ('MELODY','RH_HAND'), ('VOICE_INDEPENDENCE','BASS_PULSE'),
 ('VOICE_INDEPENDENCE','MELODY'), ('VOICE_INDEPENDENCE','SUBDIVISION'),
 ('DYNAMICS','MELODY');

ALTER TABLE exercise_skills
 ADD COLUMN target_role VARCHAR(10) NOT NULL DEFAULT 'SUPPORT',
 ADD COLUMN target_level SMALLINT NULL,
 ADD CONSTRAINT chk_exercise_skill_target_role CHECK (target_role IN ('PRIMARY','SUPPORT')),
 ADD CONSTRAINT chk_exercise_skill_target_level CHECK (target_level BETWEEN 1 AND 3);
UPDATE exercise_skills SET target_role='PRIMARY'
 WHERE exercise_id='58430000-0000-4000-8000-000000000801'
 AND skill_code IN ('PULSE','BASS_PULSE','MELODY');

CREATE TABLE exercise_criteria (
 exercise_id UUID NOT NULL REFERENCES exercises(id),
 criterion_code VARCHAR(60) NOT NULL,
 skill_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
 label VARCHAR(160) NOT NULL,
 observable_result TEXT NOT NULL,
 assessment_policy VARCHAR(40) NOT NULL DEFAULT 'MANUAL_OR_NOT_ASSESSED',
 display_order INTEGER NOT NULL,
 PRIMARY KEY(exercise_id, criterion_code),
 CONSTRAINT chk_exercise_criterion_policy CHECK
  (assessment_policy IN ('MANUAL_OR_NOT_ASSESSED','VALIDATED_MEASUREMENT'))
);
INSERT INTO exercise_criteria
(exercise_id, criterion_code, skill_code, label, observable_result, display_order) VALUES
 ('58430000-0000-4000-8000-000000000801','PULSE_STEADY','PULSE',
  'Ровный пульс','Равномерное ощущение долей без произвольных остановок',10),
 ('58430000-0000-4000-8000-000000000801','BASS_STEADY','BASS_PULSE',
  'Стабильный бас','Басовая линия не прерывается на смене мелодических фраз',20),
 ('58430000-0000-4000-8000-000000000801','MELODY_CLEAR','MELODY',
  'Разборчивая мелодия','Мелодическая линия слышна поверх баса',30);

ALTER TABLE exercises
 ADD COLUMN recommended_practice_minutes INTEGER NULL,
 ADD CONSTRAINT chk_exercise_practice_minutes CHECK
 (recommended_practice_minutes BETWEEN 10 AND 120);
UPDATE exercises SET recommended_practice_minutes=30
 WHERE code='GM-EB01' AND version='0.8B';

CREATE TABLE learner_skill_signal_history (
 id UUID PRIMARY KEY,
 instrument_profile_id UUID NOT NULL REFERENCES instrument_profiles(id) ON DELETE CASCADE,
 skill_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
 source_type VARCHAR(20) NOT NULL,
 claim_state VARCHAR(10) NULL,
 level SMALLINT NULL,
 source_reference VARCHAR(160) NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL,
 confidence NUMERIC(4,3) NULL,
 note_code VARCHAR(60) NULL,
 CONSTRAINT chk_signal_source_type CHECK (source_type IN ('SELF_REPORTED','OBSERVED','MEASURED')),
 CONSTRAINT chk_signal_claim_state CHECK (claim_state IS NULL OR claim_state IN ('CLAIMED','RETRACTED')),
 CONSTRAINT chk_signal_level CHECK (level BETWEEN 1 AND 3),
 CONSTRAINT chk_signal_confidence CHECK (confidence BETWEEN 0 AND 1),
 CONSTRAINT chk_signal_self_report CHECK (
   source_type <> 'SELF_REPORTED' OR
   (claim_state IS NOT NULL AND level IS NULL AND confidence IS NULL)
 ),
 CONSTRAINT chk_signal_evidence CHECK (
   source_type = 'SELF_REPORTED' OR
   (claim_state IS NULL AND level IS NOT NULL AND source_reference <> 'ONBOARDING')
 )
);
CREATE INDEX idx_skill_signal_profile_skill_time
 ON learner_skill_signal_history(instrument_profile_id, skill_code, recorded_at DESC, id DESC);
-- Existing questionnaire claims are mapped to familiarity only; no VERIFIED/MEASURED.
INSERT INTO learner_skill_signal_history
(id, instrument_profile_id, skill_code, source_type, claim_state, level, source_reference, recorded_at, note_code)
SELECT gen_random_uuid(), b.instrument_profile_id, 'MELODY', 'SELF_REPORTED',
 'CLAIMED', NULL, 'BACKFILL_FROM_BACKGROUND', now(), 'FAMILIARITY_ONLY'
FROM learning_backgrounds b
JOIN background_skills bs ON bs.background_id=b.id
WHERE bs.skill_code='FINGERSTYLE_MELODY';
