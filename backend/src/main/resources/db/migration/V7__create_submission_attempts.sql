-- One submission belongs to an immutable, versioned diagnostic task.
-- Raw media is stored outside PostgreSQL and outside the web root.
CREATE TABLE submission_attempts (
    id UUID PRIMARY KEY,
    diagnostic_task_id UUID NOT NULL REFERENCES diagnostic_tasks(id),
    request_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    submitted_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RECEIVED_UNASSESSED')),
    student_comment VARCHAR(2000),
    media_key VARCHAR(100) NOT NULL UNIQUE,
    media_format VARCHAR(10) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    UNIQUE (diagnostic_task_id, attempt_number),
    UNIQUE (diagnostic_task_id, request_id)
);
CREATE INDEX ix_attempts_by_task ON submission_attempts(diagnostic_task_id, attempt_number);
