-- #141: public self-registration, additive to already-applied V20/V21.
-- Legacy invited pending records remain valid; public records have no invitation.
ALTER TABLE pending_local_registrations
    ALTER COLUMN invitation_id DROP NOT NULL;
-- Keep the V20 unique invitation_id constraint: PostgreSQL allows multiple NULLs.
-- Accounts created from invitation-free verification always receive LEARNER, never OWNER.
