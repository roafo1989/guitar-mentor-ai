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
