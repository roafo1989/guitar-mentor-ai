-- FT-002-04: own recordings before any assigned exercise.
CREATE TABLE diagnostic_evidence (
    id UUID PRIMARY KEY,
    diagnostic_session_id UUID NOT NULL REFERENCES diagnostic_sessions(id),
    request_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    composer VARCHAR(200),
    student_comment VARCHAR(2000),
    source VARCHAR(30) NOT NULL DEFAULT 'STUDENT_UPLOADED'
      CHECK (source='STUDENT_UPLOADED'),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (diagnostic_session_id, request_id)
);
CREATE INDEX ix_evidence_session ON diagnostic_evidence(diagnostic_session_id,submitted_at,id);
CREATE TABLE diagnostic_evidence_media (
    id UUID PRIMARY KEY,
    evidence_id UUID NOT NULL REFERENCES diagnostic_evidence(id),
    kind VARCHAR(8) NOT NULL CHECK(kind IN ('AUDIO','SCORE')),
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    media_format VARCHAR(10) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes > 0),
    status VARCHAR(20) NOT NULL CHECK(status IN ('UPLOADED','PROCESSING','PROCESSED','FAILED')),
    processing_started_at TIMESTAMPTZ,
    processing_attempts INT NOT NULL DEFAULT 0,
    processing_error_code VARCHAR(50),
    audio_duration_seconds NUMERIC(9,3),
    finalized_at TIMESTAMPTZ,
    media_state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE'
        CHECK(media_state IN ('AVAILABLE','DELETE_PENDING','DELETED')),
    deleted_at TIMESTAMPTZ,
    delete_attempts INT NOT NULL DEFAULT 0,
    delete_error_code VARCHAR(50),
    UNIQUE(evidence_id,kind)
);
CREATE INDEX ix_evidence_media_cleanup ON diagnostic_evidence_media(media_state,finalized_at,evidence_id);
CREATE INDEX ix_evidence_audio_queue ON diagnostic_evidence_media(status,media_state,processing_started_at)
WHERE kind='AUDIO';
