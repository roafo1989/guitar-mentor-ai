# AUTH-02 / #141 — Local credentials and method linking

Implementation branch: `feature/141-auth-email-password`. **Not production-approved.** This document describes intended code paths; CI and external security QA remain acceptance gates.

## Storage and migrations

- V18 and V19 are unchanged. V20 adds `local_credentials`, `pending_local_registrations`, `local_password_tokens`.
- V21 adds `pending_local_links` for explicit addition of password authentication to an already admitted Google account.
- Email comparisons are trimmed/lowercased using `Locale.ROOT`; login is ASCII lowercase, 3–64 chars (`[a-z0-9][a-z0-9._-]{2,63}`). Password is 12–128 characters, checked server-side. Existing Google accounts never acquire a password automatically.
- Passwords are Argon2 hashes through Spring Security/Bouncy Castle; verification/reset secrets are 256-bit random tokens, stored as SHA-256 digests only. Neither passwords nor raw tokens belong in persisted event logs or response bodies.
- `access_version` is checked on each local API request and pinned to OIDC sessions after successful admission. A password change or reset bumps the version and causes existing sessions to fail on next request.
- Linking never joins accounts based on email or username. Existing `Account` and `Learner` identifiers are preserved. The Google identity must be validated by OIDC and currently unclaimed, while local credentials require email proof.

## JSON API

All mutating requests must send the CSRF header obtained from `GET /api/v1/csrf`. Browser session cookies are HttpOnly and same-origin; no password in URLs.

| Method | Route | Authentication | Result |
| --- | --- | --- | --- |
| POST | /api/v1/local-auth/register | anonymous + invitation | 202, verification email queued |
| POST | /api/v1/local-auth/verify | anonymous + delivered token | 200, creates admitted account |
| POST | /api/v1/local-auth/login | anonymous + password | 200, establishes session |
| POST | /api/v1/local-auth/request-reset | anonymous | 202, generic response |
| POST | /api/v1/local-auth/reset | anonymous + one-time token | 200, revokes old sessions |
| GET | /api/v1/local-auth/methods | current actor | method flags, no email disclosure |
| POST | /api/v1/local-auth/change | current actor + old password | 200, ends current and other sessions |
| POST | /api/v1/local-auth/link/password/start | fresh Google session | 202, email challenge |
| POST | /api/v1/local-auth/link/password/verify | same admitted account | 200, adds local method |
| POST | /api/v1/local-auth/link/google/start | local session + password | 200, OAuth linking intent |
| GET | /oauth2/authorization/guitar-mentor | OAuth2 | redirects for Google; callback binds checked Google identity |

## Local configuration

Standalone password auth (Google optional): `SPRING_PROFILES_ACTIVE=local-auth`. To enable Google too use a secure OIDC client registration. Do not run publicly without secure HTTPS proxy and a verified mail server.

Required external settings:
- `GUITAR_MENTOR_PUBLIC_ORIGIN`: exact HTTPS application origin (never an arbitrary client-supplied redirect).
- `GUITAR_MENTOR_MAIL_FROM`: sender address.
- `GUITAR_MENTOR_SMTP_HOST`, `GUITAR_MENTOR_SMTP_PORT`, `GUITAR_MENTOR_SMTP_USER`, `GUITAR_MENTOR_SMTP_PASSWORD`: server-side mail transport.
- `GUITAR_MENTOR_SMTP_AUTH`, `GUITAR_MENTOR_SMTP_STARTTLS`: SMTP transport policies.
- Local default JSESSIONID remains Secure/SameSite=Lax/HttpOnly; for loopback-only HTTP OAuth QA use the pre-existing guarded local HTTP profile, never in public environments.

If mail delivery or HTTPS origin is not configured, registration and reset must fail closed; no confirmation bypass exists. In tests use a mocked `JavaMailSender` or a local isolated SMTP capture service. Never place actual tokens in CI logs or screenshots. Raw invitation tokens are issued only by the existing operator CLI.

## Security and QA caveats

- Rate limiting inside `LocalAuthController` is a single-JVM temporary control. Distributed anti-brute-force quotas, reverse-proxy rate limits, security observability and abuse review are additional release blockers (#40), not satisfied by the in-memory limiter.
- Confirmation tokens expire after 30 minutes; password reset token after 15 minutes; invitations retain the existing 7-day policy.
- No MFA is implemented.
- External QA must exercise two users, request races, repeated emails/verification/reset, disabled account, expired sessions, CSRF (including on login), stale and concurrent sessions, network failure, no-mail configuration, provider failure, credentials and private data absent from browser storage, RU/EN, 360/768/1440 CSS px and 200% zoom.
- No production rollout or merging until these checks, reviewer approval and proper HTTPS/SMTP/deployment configuration.


## Code review — 24 October 2026

Review focus: login credential checking, transactional admission, session and ownership guards, SMTP token delivery, Google account linking, frontend auth/logout and test coverage.

- **Fixed:** Unknown-user authentication previously created an additional Argon2 hash on every attempt. The dummy hash is now created once per service instance and still checked on every unknown-user attempt. This avoids easy resource exhaustion from repeated fallback hashing while retaining a costly password check.
- **Fixed:** Outbound verification/reset mail used an escaped newline sequence instead of a real line break. Email body is now formatted correctly; `LocalCredentialPostgresTest` asserts it.
- **Previously fixed:** Both known and unknown logins now execute `encoder.matches` (instead of short-circuiting for unknown accounts).
- **Reviewed, no claim of full penetration-test coverage:** Passwords are hashed and not exposed in API DTOs, the new API retains CSRF, local actor resolution checks ACTIVE/version, account linking is explicit and not email-based, password reset revokes existing account versions, and private learner ownership remains enforced by `ActorFilter`.

### Remaining gates — NOT VERIFIED

1. Independent security assessment of invitation/confirmation races, session fixation and concurrent OAuth method-linking, failed delivery and resets.
2. End-to-end browser checks at 360/768/1440 px, RU/EN, 200% zoom and keyboard focus; screenshots and no secret leakage.
3. Real SMTP (including outage/recovery) and a configured Google OIDC provider; synthetic tests are insufficient.
4. Distributed anti-brute-force quota, log hygiene and HTTPS deployment under #40/#39.
5. Current HEAD Java/Vue CI after review commits; previous green checks do not automatically validate later SHA.

Do not merge or mark accepted until required gates have concrete evidence.
