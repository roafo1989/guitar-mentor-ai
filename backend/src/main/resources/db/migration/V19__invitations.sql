-- Opaque one-use invitation tokens are NEVER persisted in plaintext.
CREATE TABLE invitations (
  id UUID PRIMARY KEY,
  token_hash CHAR(64) NOT NULL UNIQUE,
  issuer VARCHAR(512) NOT NULL,
  email_normalized VARCHAR(320) NOT NULL,
  learner_id UUID REFERENCES learners(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','ACCEPTED','REVOKED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  accepted_at TIMESTAMPTZ,
  accepted_account_id UUID REFERENCES accounts(id) ON DELETE RESTRICT,
  CONSTRAINT chk_invite_acceptance CHECK
   ((status='ACCEPTED' AND accepted_at IS NOT NULL AND accepted_account_id IS NOT NULL)
    OR (status <> 'ACCEPTED' AND accepted_at IS NULL AND accepted_account_id IS NULL))
);
CREATE UNIQUE INDEX ux_pending_invite_email_issuer
 ON invitations(issuer,email_normalized) WHERE status='PENDING';
CREATE INDEX idx_invites_expiry ON invitations(expires_at) WHERE status='PENDING';
