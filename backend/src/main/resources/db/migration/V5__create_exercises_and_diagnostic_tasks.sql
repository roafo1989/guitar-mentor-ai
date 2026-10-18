-- Versioned preview catalog. In the first increment only GM-EB01 v0.8B is available.
CREATE TABLE exercises (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    version VARCHAR(20) NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    title VARCHAR(150) NOT NULL,
    instrument_code VARCHAR(50) NOT NULL,
    technique_code VARCHAR(50) NOT NULL,
    style_code VARCHAR(40) NOT NULL,
    difficulty VARCHAR(30) NOT NULL,
    bpm INT NOT NULL CHECK (bpm BETWEEN 20 AND 240),
    measures INT NOT NULL CHECK (measures > 0),
    meter VARCHAR(10) NOT NULL,
    author VARCHAR(100) NOT NULL,
    license VARCHAR(60) NOT NULL,
    review_status VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    score_resource VARCHAR(255) NOT NULL,
    events_resource VARCHAR(255) NOT NULL,
    UNIQUE (code, version)
);
CREATE UNIQUE INDEX uq_current_exercise_code ON exercises(code) WHERE is_current;

CREATE TABLE skill_codes (
    code VARCHAR(50) PRIMARY KEY,
    title VARCHAR(120) NOT NULL
);
INSERT INTO skill_codes (code, title) VALUES
 ('PULSE', 'Ровный музыкальный пульс'),
 ('SUBDIVISION', 'Ритмические длительности'),
 ('RH_HAND', 'Правая рука — пальцевая техника'),
 ('BASS_PULSE', 'Ровная басовая линия'),
 ('MELODY', 'Ведение мелодии'),
 ('VOICE_INDEPENDENCE', 'Независимость баса и мелодии'),
 ('LEFT_HAND', 'Левая рука и смена позиций'),
 ('DYNAMICS', 'Фразировка и динамика'),
 ('TAB_READING', 'Чтение табулатуры');

CREATE TABLE exercise_skills (
    exercise_id UUID NOT NULL REFERENCES exercises(id),
    skill_code VARCHAR(50) NOT NULL REFERENCES skill_codes(code),
    PRIMARY KEY (exercise_id, skill_code)
);

INSERT INTO exercises (id, code, version, is_current, title, instrument_code, technique_code,
    style_code, difficulty, bpm, measures, meter, author, license, review_status,
    description, score_resource, events_resource)
VALUES ('58430000-0000-4000-8000-000000000801', 'GM-EB01', '0.8B', TRUE, 'Вечерний блюз в ми', 'ACOUSTIC_GUITAR', 'FINGERSTYLE',
    'BLUES', 'INTERMEDIATE', 84, 12, '4/4', 'Guitar Mentor AI', 'CC BY 4.0',
    'ACCEPTED_FOR_MVP_PROTOTYPE',
    '12-тактовый блюз E7–A7–B7: бас большим пальцем, мелодия и аккордовые ответы.',
    'exercises/GM-EB01/evening_blues_v08B.musicxml',
    'exercises/GM-EB01/score_events_v08B.json');
INSERT INTO exercise_skills (exercise_id, skill_code) VALUES
 ('58430000-0000-4000-8000-000000000801', 'PULSE'), ('58430000-0000-4000-8000-000000000801', 'BASS_PULSE'),
 ('58430000-0000-4000-8000-000000000801', 'MELODY'), ('58430000-0000-4000-8000-000000000801', 'VOICE_INDEPENDENCE'),
 ('58430000-0000-4000-8000-000000000801', 'DYNAMICS');

-- Transition for a single assignment per diagnostic session.
ALTER TABLE diagnostic_sessions
    DROP CONSTRAINT IF EXISTS diagnostic_sessions_status_check;
ALTER TABLE diagnostic_sessions
    ADD CONSTRAINT diagnostic_sessions_status_check
    CHECK (status IN ('COLLECTING_EVIDENCE', 'READY_FOR_TASK', 'TASK_ASSIGNED'));

DROP INDEX IF EXISTS uq_diagnostic_active_instrument;
CREATE UNIQUE INDEX uq_diagnostic_active_instrument
    ON diagnostic_sessions (instrument_profile_id)
    WHERE status IN ('COLLECTING_EVIDENCE', 'READY_FOR_TASK', 'TASK_ASSIGNED');

CREATE TABLE diagnostic_tasks (
    id UUID PRIMARY KEY,
    diagnostic_session_id UUID NOT NULL UNIQUE
        REFERENCES diagnostic_sessions(id) ON DELETE CASCADE,
    exercise_id UUID NOT NULL REFERENCES exercises(id),
    assigned_at TIMESTAMPTZ NOT NULL
);
