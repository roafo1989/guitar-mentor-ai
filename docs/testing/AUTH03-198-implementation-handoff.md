# #198 / AUTH-03 — developer QA and owner UAT handoff

Date: 2026-10-25. Branch: `feat/198-auth03-ui-brand`. PR: #199. Base: main 4426becd8aa737903ff79d46720703cf5dd115a4.

## Scope implemented
- Single login/register form, link-based switching, no third Google tab; one click starts existing direct/bridge OAuth.
- Approved original logo PNG reused in auth/header and versioned 32/64 favicon. Official Google icon and Google Sans subset kept unchanged, local assets and licenses copied.
- Generic registration and password recovery confirmation; manual 64-character code fallback, link token scrubbed by existing startup/router helpers.
- Expired link state, safe OAuth failure marker cleanup, pending prevention and request epoch invalidation on context switch.
- Password input toggles, semantics, RU/EN copy, responsive auth card, scoped CSS.
- Existing API and backend login/CSRF/actor/ownership contracts unchanged.

## Verification record
- Targeted source and security review: completed by implementation assistant; **not independent code review**.
- Frontend `npm test`, `test:intake`, `test:tuner`, default and Light Minimal `npm run build`, dependency audit, existing Chrome score reader and UI smoke: rely on latest GitHub Actions green run at PR #199 (verify final HEAD, not older run).
- Existing auth-security tests cover CSRF/session/redirect/token scrub. Added AUTH-03 regression assertions for assets, Google mode/CTA and generic responses. **These are source contract checks, not mounted browser end-to-end coverage**.
- Full 24 TC-AUTH03 scenarios: **not 24 E2E PASS**. Real SMTP/OAuth/account security flows, screenshot/keyboard/screen reader responsive inspection and user visual confirmation still need execution in configured secure environment.
- No public rollout, merge or issue closure.

## Owner acceptance (after deploy or local secure run)
1. Start existing `scripts/local-auth-unified.ps1` in secure configured environment, open https://guitar-mentor (do not use insecure dev prototype for this check).
2. In RU and EN: check default login and link to register. There must be **no Google tab**, but a color-G `Continue with Google` action on both forms. Try Google with empty local inputs; only one activation.
3. Register a fresh test account. Confirm the neutral check-email wording; follow the email verification link, verify and sign in. Do not expose email tokens in screenshots.
4. Request recovery for existing/unknown test email. Both must show the same safe message. Reset with valid link; sign in with new password. Repeat an expired/used link.
5. Check failed Google login and return/back behavior, session expiry, logout, and password show/hide with keyboard.
6. Inspect logo in header/card and favicon, Home navigation by brand, and views at 320/360/768/1440 CSS px, 200% zoom and both languages.
7. Spot-check authenticated profile and lessons including tuner/metronome without regressions.

## Outstanding before owner signoff
Real configured Google/SMTP integration smoke, all relevant TC-AUTH03 interaction evidence, actual RU/EN screenshots, independent reviewer approval and owner visual UAT. Mark scenarios BLOCKED/NOT_RUN rather than inventing PASS. PR remains unmerged until signoff.
