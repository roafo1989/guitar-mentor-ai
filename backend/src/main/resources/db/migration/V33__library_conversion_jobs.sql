-- FT-013-02 / #170-A: durable, owner-scoped conversion jobs and DB-backed worker slots.
CREATE TABLE library_conversion_jobs (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE RESTRICT,
    -- Deliberately no FK to library_assets: this is an immutable lineage snapshot.
    -- Active jobs are protected by deletion workers; terminal history survives asset deletion.
    source_asset_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    source_format VARCHAR(8) NOT NULL CHECK (source_format IN ('GP3','GP4','GP5')),
    source_byte_size BIGINT NOT NULL CHECK (source_byte_size BETWEEN 1 AND 20971520),
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'QUEUED','RUNNING','AWAITING_TRACK_SELECTION','CANCEL_REQUESTED',
        'SUCCEEDED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED'
    )),
    stage VARCHAR(32) CHECK (stage IS NULL OR stage IN (
        'VALIDATING_SOURCE','IMPORTING_SCORE','RENDERING_PDF',
        'SYNTHESIZING_AUDIO','VERIFYING_OUTPUTS'
    )),
    progress SMALLINT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    tracks JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tracks)='array'),
    selected_track_index INTEGER CHECK (selected_track_index IS NULL OR selected_track_index>=0),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 2),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    pdf_asset_id UUID,
    audio_asset_id UUID,
    error_code VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>=1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE(owner_account_id,idempotency_key),
    CHECK (
      (state='SUCCEEDED' AND pdf_asset_id IS NOT NULL AND audio_asset_id IS NOT NULL
        AND finished_at IS NOT NULL AND progress=100)
      OR
      (state<>'SUCCEEDED' AND pdf_asset_id IS NULL AND audio_asset_id IS NULL)
    ),
    CHECK (
      (state IN ('SUCCEEDED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED') AND finished_at IS NOT NULL)
      OR
      (state NOT IN ('SUCCEEDED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED') AND finished_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_library_conversion_active_owner
    ON library_conversion_jobs(owner_account_id)
    WHERE state IN ('QUEUED','RUNNING','AWAITING_TRACK_SELECTION','CANCEL_REQUESTED');
CREATE INDEX idx_library_conversion_queue
    ON library_conversion_jobs(state,created_at,id)
    WHERE state='QUEUED';
CREATE INDEX idx_library_conversion_material
    ON library_conversion_jobs(owner_account_id,material_id,created_at DESC);
CREATE INDEX idx_library_conversion_lease_state
    ON library_conversion_jobs(state,updated_at)
    WHERE state IN ('RUNNING','CANCEL_REQUESTED');

-- Exactly two durable global worker slots. A JVM semaphore cannot enforce this
-- limit across restarts or multiple application instances.
CREATE TABLE library_conversion_worker_slots (
    slot_number SMALLINT PRIMARY KEY CHECK (slot_number IN (1,2)),
    job_id UUID REFERENCES library_conversion_jobs(id) ON DELETE RESTRICT,
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    CHECK (
      (job_id IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND heartbeat_at IS NULL)
      OR
      (job_id IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND heartbeat_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_library_conversion_worker_job
    ON library_conversion_worker_slots(job_id) WHERE job_id IS NOT NULL;
INSERT INTO library_conversion_worker_slots(slot_number) VALUES (1),(2);
