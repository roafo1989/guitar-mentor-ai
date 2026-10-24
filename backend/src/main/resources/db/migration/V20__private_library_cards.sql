-- #160: private catalogue; uploaded bytes are handled separately in #161.
CREATE TABLE library_materials (
 id UUID PRIMARY KEY,
 owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
 material_type VARCHAR(24) NOT NULL CHECK (material_type IN ('EXERCISE','ETUDE','COMPOSITION','OTHER')),
 description TEXT NOT NULL DEFAULT '',
 status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING' CHECK(status IN ('UPLOADING','READY','FAILED')),
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_library_owner_cursor ON library_materials(owner_account_id,created_at DESC,id DESC);
CREATE TABLE library_assets (
 id UUID PRIMARY KEY,
 material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE CASCADE,
 storage_key TEXT NOT NULL UNIQUE,
 original_filename VARCHAR(255) NOT NULL,
 media_type VARCHAR(120) NOT NULL,
 size_bytes BIGINT NOT NULL CHECK(size_bytes > 0),
 checksum_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_library_assets_material ON library_assets(material_id);
CREATE TABLE library_upload_operations (
 id UUID PRIMARY KEY,
 owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 material_id UUID REFERENCES library_materials(id) ON DELETE SET NULL,
 idempotency_key VARCHAR(128) NOT NULL,
 status VARCHAR(24) NOT NULL CHECK(status IN ('STARTED','COMPLETE','FAILED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(owner_account_id,idempotency_key)
);
