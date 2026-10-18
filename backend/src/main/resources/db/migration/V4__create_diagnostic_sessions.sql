-- DEV-05A: one active initial diagnostic per instrument.
-- The final task and media lifecycle will be modelled in later migrations.
CREATE TABLE diagnostic_sessions (
    id UUID PRIMARY KEY,
    instrument_profile_id UUID NOT NULL
        REFERENCES instrument_profiles(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('COLLECTING_EVIDENCE', 'READY_FOR_TASK')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_diagnostic_active_instrument
    ON diagnostic_sessions (instrument_profile_id)
    WHERE status IN ('COLLECTING_EVIDENCE', 'READY_FOR_TASK');
