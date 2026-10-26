-- #232. Existing ASCII usernames and unique indexes remain unchanged.
-- Script/letter policy and case/NFC canonicalization are enforced by CredentialPolicy.
-- Database bounds/control guards intentionally avoid locale-dependent POSIX alphabet rules.
ALTER TABLE local_credentials DROP CONSTRAINT local_login_shape;
ALTER TABLE local_credentials ADD CONSTRAINT local_login_shape CHECK (
    char_length(login_normalized) BETWEEN 3 AND 64
    AND login_normalized = normalize(login_normalized, NFC)
    AND login_normalized !~ '[[:space:][:cntrl:]@]'
);
ALTER TABLE pending_local_registrations ADD CONSTRAINT pending_login_shape CHECK (
    char_length(login_normalized) BETWEEN 3 AND 64
    AND login_normalized = normalize(login_normalized, NFC)
    AND login_normalized !~ '[[:space:][:cntrl:]@]'
);
ALTER TABLE pending_local_links ADD CONSTRAINT pending_link_login_shape CHECK (
    char_length(login_normalized) BETWEEN 3 AND 64
    AND login_normalized = normalize(login_normalized, NFC)
    AND login_normalized !~ '[[:space:][:cntrl:]@]'
);
