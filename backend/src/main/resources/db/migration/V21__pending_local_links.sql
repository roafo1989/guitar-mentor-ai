-- #141: confirmed, session-bound second credential linking. Never merge accounts by email.
CREATE TABLE pending_local_links (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
  email_normalized VARCHAR(320) NOT NULL UNIQUE,
  login_normalized VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  verification_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_local_links_expiry ON pending_local_links(expires_at);
