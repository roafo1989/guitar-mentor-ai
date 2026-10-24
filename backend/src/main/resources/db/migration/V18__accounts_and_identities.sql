-- Identity admission is deliberately separate from invitations (#42).
-- Existing local learners remain unclaimed until an operator explicitly binds them.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    learner_id UUID UNIQUE REFERENCES learners(id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER','LEARNER')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    access_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE account_identities (
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX idx_account_identities_account ON account_identities(account_id);
