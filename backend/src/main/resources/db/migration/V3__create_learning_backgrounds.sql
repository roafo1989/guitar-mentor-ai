-- The questionnaire is scoped to one learner instrument profile.
CREATE TABLE learning_backgrounds (
    id UUID PRIMARY KEY,
    instrument_profile_id UUID NOT NULL UNIQUE
        REFERENCES instrument_profiles(id) ON DELETE CASCADE,
    experience_level VARCHAR(20) NOT NULL
        CHECK (experience_level IN ('NONE','BEGINNER','INTERMEDIATE','ADVANCED')),
    years_playing INTEGER NOT NULL CHECK (years_playing BETWEEN 0 AND 60),
    practice_minutes INTEGER NOT NULL CHECK (practice_minutes BETWEEN 10 AND 120),
    sessions_per_week INTEGER NOT NULL CHECK (sessions_per_week BETWEEN 1 AND 7),
    notes VARCHAR(1000) NOT NULL
);

CREATE TABLE background_skills (
    background_id UUID NOT NULL REFERENCES learning_backgrounds(id) ON DELETE CASCADE,
    skill_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (background_id, skill_code)
);

CREATE TABLE background_goals (
    background_id UUID NOT NULL REFERENCES learning_backgrounds(id) ON DELETE CASCADE,
    goal_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (background_id, goal_code)
);

CREATE TABLE background_known_pieces (
    background_id UUID NOT NULL REFERENCES learning_backgrounds(id) ON DELETE CASCADE,
    piece_title VARCHAR(120) NOT NULL,
    piece_artist VARCHAR(120) NOT NULL
);
