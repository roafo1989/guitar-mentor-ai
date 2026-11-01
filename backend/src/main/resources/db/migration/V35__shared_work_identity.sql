-- #204: shared bibliographic identity; no migration of the private V27 corpus.
CREATE TABLE community_works (
 id UUID PRIMARY KEY,
 title VARCHAR(150) NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 150),
 artist VARCHAR(150) NOT NULL DEFAULT '',
 normalized_title VARCHAR(150) NOT NULL,
 normalized_artist VARCHAR(150) NOT NULL,
 identity_status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' CHECK(identity_status IN ('CANDIDATE','VERIFIED','RETIRED')),
 created_by_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_community_work_identity ON community_works(normalized_title,normalized_artist);
ALTER TABLE library_materials ADD CONSTRAINT uq_material_owner UNIQUE(owner_account_id,id);
CREATE TABLE library_material_work_links (
 material_id UUID PRIMARY KEY REFERENCES library_materials(id) ON DELETE RESTRICT,
 owner_account_id UUID NOT NULL,
 work_id UUID NOT NULL REFERENCES community_works(id) ON DELETE RESTRICT,
 created_by_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(owner_account_id,material_id) REFERENCES library_materials(owner_account_id,id) ON DELETE RESTRICT
);
CREATE INDEX idx_material_work ON library_material_work_links(work_id);
ALTER TABLE library_publications ADD COLUMN work_id UUID REFERENCES community_works(id) ON DELETE RESTRICT;
CREATE INDEX idx_publication_work ON library_publications(work_id,created_at DESC,id DESC) WHERE state='PUBLISHED';
CREATE TABLE community_work_events (
 id UUID PRIMARY KEY,
 work_id UUID NOT NULL REFERENCES community_works(id) ON DELETE RESTRICT,
 actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 action VARCHAR(32) NOT NULL,
 previous_work_id UUID REFERENCES community_works(id) ON DELETE RESTRICT,
 new_work_id UUID REFERENCES community_works(id) ON DELETE RESTRICT,
 reason_code VARCHAR(500) NOT NULL DEFAULT '',
 request_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE community_work_operations (
 actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 idempotency_key UUID NOT NULL,
 fingerprint TEXT NOT NULL,
 result_id UUID NOT NULL,
 result_revision BIGINT NOT NULL,
 PRIMARY KEY(actor_account_id,idempotency_key)
);
CREATE TABLE community_work_split_plans (
 id UUID PRIMARY KEY,
 actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 work_id UUID NOT NULL REFERENCES community_works(id) ON DELETE RESTRICT,
 work_revision BIGINT NOT NULL,
 fingerprint TEXT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '15 minutes',
 result_id UUID REFERENCES community_works(id) ON DELETE RESTRICT
);
