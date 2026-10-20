-- #68: immutable request payload digest ensures retries cannot silently substitute media.
-- Nullable for records written before this additive migration: ambiguous legacy retries reject.
ALTER TABLE diagnostic_evidence
    ADD COLUMN request_fingerprint CHAR(64);
