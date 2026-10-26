# #232 — Editable account login and credential policy

Dependent on #229 / PR #231. Separate branch `feature/232-account-login-policy`; neither issue is accepted or merged by this work.

## Behavior

Account settings show the current login and an Edit action. Save requires the current application password. An occupied login preserves the form and explains the conflict; success clears the password and confirms the identifier for the next sign-in. Failed initial reads offer Retry without an editable guessed value. Unsaved changes use the existing navigation guard. Responses from a previous account cannot populate the current form.

Only the authenticated actor’s credential is changed. Account/learner IDs, learning history, display name, verified email, Google identity and current sessions remain intact. Email sign-in still works; the old username stops working. A Google-only account can add password sign-in through the existing verified-email flow, after which the login card refreshes.

## Shared rules

- Login: 3–64 Unicode code points after trimming, NFC normalization and locale-independent lowercase (then NFC again). Latin/Cyrillic letters, ASCII digits, dot, underscore and hyphen; start with a letter or digit. Combining marks are allowed only after a Latin/Cyrillic letter or another permitted mark. Mixed Latin/Cyrillic names are allowed. Spaces, control characters, email addresses, other scripts and emoji are rejected. Visually similar characters remain distinct; no transliteration or automatic account matching occurs.
- New passwords: 6–128 Unicode code points, without trimming or normalization. Registration, reset, password change and password-method linking use the same rule in Java and TypeScript. Existing hashes remain unchanged. This minimum is the owner’s explicit product decision on 26 October 2026.
- Argon2 hashing, CSRF, active-account resolution, password-change/reset session revocation and existing throttling remain in place. The existing single-instance rate limiter is retained, including 8 username-change attempts per minute per remote address.

## API and concurrency

`GET /api/v1/local-auth/username` returns `{ "login": "..." }` or `null` for an account without local credentials. `POST` at the same route accepts `newLogin` and `currentPassword`; it derives ownership exclusively from the actor. Responses: 200 success, 400 invalid input/confirmation, 401 unauthenticated, 409 occupied, 429 throttled. Mutations require the existing CSRF header.

Username claims are serialized by the transaction-scoped PostgreSQL advisory lock `(232, 1)` across registration, verification, password linking and rename. Expired reservations are cleaned inside that lock. Availability checks cover active credentials and both unexpired pending tables. Unique indexes remain as the final guard. This favors predictable low-volume account operations; unrelated username claims serialize while their transaction (including registration mail delivery) is open.

## Migration / deployment

Flyway V30 replaces the ASCII credential constraint and adds NFC/length/control guards to pending tables. Script validation lives in `CredentialPolicy`; SQL guards deliberately avoid locale-dependent alphabet matching. No existing migration, credential, password hash or identifier is rewritten. Existing ASCII rows satisfy the new constraints.

Restart the backend to apply V30 and serve the API; rebuild/restart the frontend as well. This branch includes all pending PR #231 changes. Before rolling back to the ASCII-only application, account for any newly created Cyrillic usernames; do not blindly restore the old constraint over those rows. No destructive down migration is supplied.

Validation evidence is in [../testing/issue-232-account-credentials.md](../testing/issue-232-account-credentials.md).
