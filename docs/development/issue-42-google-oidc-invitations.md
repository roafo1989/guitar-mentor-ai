# #42 — Google OpenID Connect and invitation admission

Status: implementation branch `feature/42-google-invitations` depends on PR #132; not a production approval. No public deployment.

## Provider registration (operator-only)

1. In [Google Cloud Console](https://console.cloud.google.com/), create/select a dedicated project.
2. Open **Google Auth Platform** → **Branding**, set app name and support email, and choose suitable Audience. For an external application in testing, add the exact Google accounts of your test users under **Test users**. Configure only minimum scopes `openid`, `profile`, `email`.
3. **Clients** → **Create client** → **Web application**. Add the actual authorized redirect URI matching the *same-origin* installation:
   `https://<approved-real-domain>/login/oauth2/code/guitar-mentor`.
   Google may reject a custom single-label hostname such as `https://guitar-mentor`; localhost HTTP loopback is a separate explicitly configured developer callback, and the secure session cookie setting must be considered. **Do not register a fictitious public domain or open any ports yet** (#39).
4. Store the OAuth Client ID and Client Secret securely on the target machine, not in Git/chat/frontend or URLs. Use environment variables `GUITAR_MENTOR_OIDC_CLIENT_ID`, `GUITAR_MENTOR_OIDC_CLIENT_SECRET`; `SPRING_PROFILES_ACTIVE=secure`. Google issuer is `https://accounts.google.com`.
5. Authenticate at `/oauth2/authorization/guitar-mentor`. The registered callback remains `/login/oauth2/code/guitar-mentor`. Behind a TLS proxy, forward only trusted origin headers (#39).

## Invitation lifecycle

Activation of a *Google-verified* email requires a 256-bit random, one-use hex token from a local operator CLI. PostgreSQL stores SHA-256 token hash only; no user-created password. Invitation expires 7 days after issuance; status is PENDING/ACCEPTED/REVOKED. Provider `iss`, `sub`, `email_verified`, and matching email are checked from the validated OIDC principal, never from request JSON. A token is not an identity: the user must first sign in through Google. On the secure frontend, sign in, paste token in the invitation field, activate; subsequent sign-ins use immutable `issuer + subject`. Existing learner links require explicit UUID.

**Only on the private trusted operator host**, with DB environment configured and after applying Flyway V19, run the backend with `spring.main.web-application-type=none` and a *one-time* admin action. This runs the same Spring app in headless mode with no HTTP listener.

PowerShell examples (do not capture/commit/paste the printed secret):

```powershell
cd D:\Development\guitar-mentor-ai\backend
$env:GUITAR_MENTOR_SECURITY_ENABLED = 'true'
$env:SPRING_PROFILES_ACTIVE = 'secure'
# Set DB/OIDC variables as protected local environment values beforehand.
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --guitar-mentor.invitation-admin.action=bootstrap-owner --guitar-mentor.invitation-admin.email=you@example.com"
```

To invite an ordinary learner substitute `action=invite`; set `email=user@example.com`; for an **explicitly verified** existing profile add `--guitar-mentor.invitation-admin.learner=<UUID>`. For revocation of pending invitation use `action=revoke --guitar-mentor.invitation-admin.id=<invitation UUID>`; to block a learner account use `action=disable --guitar-mentor.invitation-admin.id=<account UUID>`. These commands do not expose a remotely accessible admin API. Never redirect CLI stdout to CI logs; printed `INVITATION_TOKEN` is a credential and must be communicated privately. Use a local password manager. Listing invitation/account IDs requires a private DB operator view until a dedicated redacted list CLI is implemented.

## Operational and acceptance restrictions

- OWNER is created by a one-time, explicit invitation. To preserve an existing learner profile for the first OWNER, pass its UUID explicitly with the same `--guitar-mentor.invitation-admin.learner=<UUID>` option; this performs no display-name or email-based matching. A database partial unique index blocks multiple concurrently active owners; the generic `disable` operation refuses OWNER. Recovery procedure remains an operator concern.
- Cross-account access remains blocked by #38. Immediate learner disable is rechecked each request. Revoking a PENDING invite does not revoke an already active account.
- Browser/Google IdP end-to-end tests, actual DNS and HTTPS/cookies, parallel activation requests and operator recovery are NOT_RUN and release blockers. This implementation still requires full security review and integration with #39/#40.
- Do not merge #42 before #132 or claim that the secure profile was deployed.


## Local Google sign-in QA on Windows — one-origin localhost flow

The current Caddy alias `https://guitar-mentor` cannot be used with Google's registered
`http://localhost:8080/login/oauth2/code/guitar-mentor` without crossing host/cookie
boundaries. Use a single browser origin: **`http://localhost:5173`** (Vite proxy to
Spring Boot on loopback) and register exactly:
`http://localhost:5173/login/oauth2/code/guitar-mentor` as another authorized
redirect URI in **Google Auth Platform → Clients → Guitar Mentor AI Web**.
JavaScript origins remain empty. Do not change `vite.config.ts` to bind public interfaces.

For local *synthetic-data* QA, fetch the #42 branch (depends on #38), with a clean
Git working tree; do not merge the Draft PR. In backend PowerShell session:

```powershell
cd D:\Development\guitar-mentor-ai\backend
$env:SPRING_PROFILES_ACTIVE = 'secure,local-google'
$env:GUITAR_MENTOR_SECURITY_ENABLED = 'true'
$env:GUITAR_MENTOR_BIND_ADDRESS = '127.0.0.1'
$env:GUITAR_MENTOR_OIDC_CLIENT_ID = Read-Host 'Google OAuth Client ID'
$secureClientSecret = Read-Host 'Google OAuth Client Secret' -AsSecureString
$env:GUITAR_MENTOR_OIDC_CLIENT_SECRET = [System.Net.NetworkCredential]::new('', $secureClientSecret).Password
# Set the database password with the same no-echo Read-Host pattern, if not set:
$secureDbPassword = Read-Host 'PostgreSQL password' -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', $secureDbPassword).Password
.\mvnw.cmd spring-boot:run
```

The backend on port 8080 is HTTP **only on loopback**. The `local-google`
profile explicitly sets non-Secure session and CSRF cookies for **localhost-only**
QA; `secure` still enables server-side authentication, CSRF and ownership.
The security filter-chain fails startup if local-HTTP mode is enabled with an
external bind address or authentication disabled. Never apply this profile to
production, staging, LAN, tunneling or a public reverse proxy.

In a second PowerShell session:

```powershell
cd D:\Development\guitar-mentor-ai\frontend
$env:VITE_GUITAR_MENTOR_AUTH_REQUIRED = 'true'
npm run dev
```

Open **http://localhost:5173** (not the usual Caddy `https://guitar-mentor`).
Click Google sign-in. The callback lands at Vite `5173`, gets proxied by Vite to
Spring `8080`; both routes appear as one localhost browser origin.
OIDC sign-in alone gives no personal application access: activate a one-use owner
invitation using the separately run private headless CLI after first OIDC sign-in.

Do not copy client secret, the one-time invite token, DB password or login cookies
to GitHub, screenshot, browser console or conversation. Only Google Client ID
(the public identifier) and nonsecret command output/error codes can be reported.

For the eventual external deployment (#39), remove local-google profile and
configure real HTTPS redirect URI and Secure cookies; never reuse localhost values.
