-- FT-011-01/#160: account-owned private originals, no association with exercises.
CREATE TABLE library_materials (
 id UUID PRIMARY KEY,
 owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 title VARCHAR(150) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 150),
 material_type VARCHAR(16) NOT NULL CHECK (material_type IN ('EXERCISE','ETUDE','COMPOSITION','OTHER')),
 author VARCHAR(150),
 state VARCHAR(16) NOT NULL CHECK(state IN ('READY','DELETING')),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision >= 1),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_library_owner_state_cursor
 ON library_materials(owner_account_id,state,created_at DESC,id DESC);

CREATE TABLE library_assets (
 id UUID PRIMARY KEY,
 material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE RESTRICT,
 object_key VARCHAR(255) NOT NULL UNIQUE,
 original_name VARCHAR(255) NOT NULL,
 format VARCHAR(8) NOT NULL CHECK(format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP')),
 byte_size BIGINT NOT NULL CHECK(byte_size > 0 AND byte_size <= 20971520),
 sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_library_assets_material ON library_assets(material_id);

CREATE TABLE library_upload_operations (
 id UUID PRIMARY KEY,
 owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 idempotency_key UUID NOT NULL,
 fingerprint CHAR(64),
 manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
 lease_owner UUID,
 last_lease_at TIMESTAMPTZ,
 state VARCHAR(16) NOT NULL CHECK(state IN ('RECEIVING','COMMITTING','SUCCEEDED','FAILED')),
 reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0),
 reserved_files BIGINT NOT NULL DEFAULT 0 CHECK(reserved_files>=0),
 material_id UUID REFERENCES library_materials(id) ON DELETE RESTRICT,
 expires_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 terminal_code VARCHAR(64),
 UNIQUE(owner_account_id,idempotency_key)
);
CREATE INDEX idx_library_upload_recovery ON library_upload_operations(state,updated_at);

CREATE TABLE library_account_usage (
 account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE RESTRICT,
 charged_bytes BIGINT NOT NULL DEFAULT 0 CHECK(charged_bytes>=0),
 charged_files BIGINT NOT NULL DEFAULT 0 CHECK(charged_files>=0),
 reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0),
 reserved_files BIGINT NOT NULL DEFAULT 0 CHECK(reserved_files>=0),
 revision BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE library_deletion_jobs (
 material_id UUID PRIMARY KEY REFERENCES library_materials(id) ON DELETE RESTRICT,
 state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','RUNNING','RETRY')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_error_code VARCHAR(64)
);
CREATE INDEX idx_library_deletion_due ON library_deletion_jobs(state,next_attempt_at);
