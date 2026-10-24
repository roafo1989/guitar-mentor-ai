-- AUTH-02 #141. Additive schema; do not rewrite applied V18/V19.
CREATE TABLE local_credentials (
  account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  email_normalized VARCHAR(320) NOT NULL UNIQUE,
  login_normalized VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  email_verified_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT local_login_shape CHECK (login_normalized ~ '^[a-z0-9][a-z0-9._-]{2,63}$')
);
-- Reservation prevents parallel signups claiming the same login or email before verification.
CREATE TABLE pending_local_registrations (
  id UUID PRIMARY KEY,
  invitation_id UUID NOT NULL UNIQUE REFERENCES invitations(id) ON DELETE CASCADE,
  email_normalized VARCHAR(320) NOT NULL UNIQUE,
  login_normalized VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  verification_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pending_local_registrations_expiry ON pending_local_registrations(expires_at);
CREATE TABLE local_password_tokens (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('RESET')),
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX local_password_tokens_active ON local_password_tokens(account_id,expires_at)
  WHERE consumed_at IS NULL;
