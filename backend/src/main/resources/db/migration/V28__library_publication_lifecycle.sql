-- FT-012-01/#165: explicit immutable publication snapshots; never expose imported index.
-- No ON DELETE CASCADE from source assets: publication snapshots hold immutable bibliographic
-- fields and opaque asset keys, but access always checks current PUBLISHED + READY.
CREATE TABLE library_publications (
 id UUID PRIMARY KEY,
 material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE RESTRICT,
 owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 version BIGINT NOT NULL CHECK(version>=1),
 publication_revision BIGINT NOT NULL DEFAULT 1 CHECK(publication_revision>=1),
 idempotency_key UUID NOT NULL,
 request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
 material_revision BIGINT NOT NULL CHECK(material_revision>=1),
 title VARCHAR(150) NOT NULL,
 material_type VARCHAR(16) NOT NULL CHECK(material_type IN ('EXERCISE','ETUDE','COMPOSITION','OTHER')),
 author VARCHAR(150),
 state VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
  CHECK(state IN ('PUBLISHED','WITHDRAWN','HIDDEN')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 ended_at TIMESTAMPTZ,
 UNIQUE(material_id,version),
 UNIQUE(owner_account_id,idempotency_key),
 CHECK ((state='PUBLISHED' AND ended_at IS NULL) OR (state<>'PUBLISHED' AND ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_library_active_publication ON library_publications(material_id)
 WHERE state='PUBLISHED';
CREATE INDEX idx_library_published_cursor ON library_publications(created_at DESC,id DESC)
 WHERE state='PUBLISHED';

CREATE TABLE library_publication_assets (
 publication_id UUID NOT NULL REFERENCES library_publications(id) ON DELETE RESTRICT,
 asset_id UUID NOT NULL,
 original_name VARCHAR(255) NOT NULL,
 format VARCHAR(8) NOT NULL CHECK(format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP')),
 byte_size BIGINT NOT NULL CHECK(byte_size>0 AND byte_size<=20971520),
 sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 object_key VARCHAR(255) NOT NULL,
 provenance VARCHAR(16) NOT NULL CHECK(provenance IN ('CREATED','LICENSED','PUBLIC_DOMAIN','PERMISSION')),
 rights_basis VARCHAR(16) NOT NULL CHECK(rights_basis IN ('OWN_WORK','LICENSE','PUBLIC_DOMAIN','PERMISSION')),
 rights_evidence VARCHAR(500) NOT NULL CHECK(length(btrim(rights_evidence)) BETWEEN 8 AND 500),
 attribution VARCHAR(2000) NOT NULL DEFAULT '',
 PRIMARY KEY(publication_id,asset_id)
);
CREATE INDEX idx_library_publication_assets_ref ON library_publication_assets(asset_id);

CREATE TABLE library_publication_events (
 id UUID PRIMARY KEY,
 publication_id UUID NOT NULL REFERENCES library_publications(id) ON DELETE RESTRICT,
 actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 action VARCHAR(16) NOT NULL CHECK(action IN ('PUBLISH','WITHDRAW','HIDE','DELETE_WITHDRAW')),
 reason VARCHAR(500),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_library_publication_events ON library_publication_events(publication_id,created_at);
