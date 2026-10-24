# #38 — Security foundation (FT-007-01)

Status: implementation branch `feature/38-security-foundation`; public deployment remains BLOCKED pending #42/#39/#40.

## Architecture

In the `secure` Spring profile, Spring Security performs OIDC Authorization Code authentication through a server-configured IdP. Only `issuer + subject` from the validated ID token identifies the user. `account_identities` maps the pair to `accounts`. Each API request rechecks ACTIVE status using the database; there is no trust in a UUID supplied by localStorage, URL, or JSON. Even OWNER does not automatically access other learners' recordings or assessments.

V18 adds accounts and identity mappings without backfilling old local profiles; automatic email/name matching would allow account takeover. #42 owns the invite/admission lifecycle, explicit existing-learner binding and first OWNER bootstrap. In secure mode direct POST to `/api/v1/learners` is forbidden.

All `/api/**` calls require authentication and a currently active, admitted account. Every `/api/v1/learners/{learnerId}/...` request additionally compares the URL UUID to the server-side account's learner UUID. Unauthorized callers receive JSON 401, disabled/unmapped identity gets 403, foreign learner gets safe 404. API responses are non-cacheable; `/actuator/**` is blocked. This route-level check also covers new nested GET/HEAD paths automatically.

Session state is managed server-side with HttpOnly/Secure/SameSite=Lax JSESSIONID, session ID rotation, idle timeout (default 30m), configurable absolute lifetime (default 720m), CSRF tokens for unsafe methods including upload/logout, and logout invalidation. The dedicated CSRF token is intentionally readable to JavaScript; it is NOT the session cookie. No OAuth tokens are written to browser storage.

## Running locally

Existing Windows localhost workflow remains available with **explicit** `GUITAR_MENTOR_SECURITY_ENABLED=false` (never the default), **only on loopback**, with synthetic data. The default is secured and fails to start without OIDC configuration. Explicit insecure mode is only for synthetic test data. Attempting this mode with a non-loopback `GUITAR_MENTOR_BIND_ADDRESS` fails startup. Local HTTPS/Caddy proxy and local development are not internet security controls.

For an *isolated* OIDC QA installation, configure the following **outside Git**, with test credentials and a valid OIDC issuer:

- `SPRING_PROFILES_ACTIVE=secure`
- `GUITAR_MENTOR_OIDC_ISSUER` — registered IdP issuer URL
- `GUITAR_MENTOR_OIDC_CLIENT_ID`, `GUITAR_MENTOR_OIDC_CLIENT_SECRET`
- `GUITAR_MENTOR_SESSION_IDLE_TIMEOUT=30m` (adjust in QA as necessary)
- `GUITAR_MENTOR_BIND_ADDRESS=127.0.0.1`
- Vite local secure-mode override: `VITE_GUITAR_MENTOR_AUTH_REQUIRED=true`

Register the OIDC callback `{baseUrl}/login/oauth2/code/guitar-mentor` with the IdP. On a local reverse proxy, verify forwarded origin and redirect correctness before login; final trusted-proxy/staging configuration is #39. Never hardcode a client secret in frontend config. Production accounts cannot log into application data until #42 provisions known issuer/subject identities; that is the intentional deny-by-default boundary.

`GET /api/v1/me` provides `accountId`, `learnerId`, and `role` for the current admitted actor. `GET /api/v1/csrf` returns `headerName` and `token` for unsafe API requests. `POST /logout` requires CSRF and returns 204 on success. Vue never automatically retries a POST after an expired session.

## Security release gate

This branch is **not** a public-release go/no-go. Before merging or exposing users, perform provider-specific login and negative state/nonce/issuer tests, multi-user HTTP BOLA tests against every API and media route, CSRF multipart/logout checks, disabled-account-with-existing-session checks, QA in RU/EN, and independent review. Run on a pinned commit SHA. Invitation/bootstrap is #42; trusted HTTPS proxy #39; backup/limits/go-no-go #40.
