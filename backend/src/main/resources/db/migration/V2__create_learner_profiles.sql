-- First vertical slice: learner identity, instrument profiles and style preferences.
-- Binary audio/video are never stored in relational tables.
CREATE TABLE learners (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE instrument_profiles (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES learners(id) ON DELETE CASCADE,
    instrument_code VARCHAR(50) NOT NULL,
    technique_code VARCHAR(50) NOT NULL,
    CONSTRAINT uq_instrument_learner_code UNIQUE (learner_id, instrument_code)
);

CREATE TABLE instrument_music_preferences (
    instrument_profile_id UUID NOT NULL REFERENCES instrument_profiles(id) ON DELETE CASCADE,
    style_code VARCHAR(40) NOT NULL,
    rating VARCHAR(10) NOT NULL CHECK (rating IN ('LIKE', 'NEUTRAL', 'EXCLUDE')),
    PRIMARY KEY (instrument_profile_id, style_code)
);
