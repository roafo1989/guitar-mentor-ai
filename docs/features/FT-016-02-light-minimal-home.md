# FT-016-02 / #149 — Light Minimal Home

## Delivery and source of truth
The opt-in build flag `VITE_GUITAR_MENTOR_LIGHT_MINIMAL=true` enables the new home presentation. The same flag from #148 can be switched off to restore the pre-existing Home; it does not change backend schema, stored user data or authentication rules.

Business state is resolved by existing `resolveHomeSummary` using the existing learner, onboarding, diagnostic and learning services. The homepage is **read only** and never creates a diagnostic, lesson, attempt or session. No new REST endpoints.

| Step | Primary action | Contextual heading/description |
| --- | --- | --- |
| profile | /profile | Configure instrument first; no instrument-background GET |
| onboarding | /onboarding | Tell us about experience and goals |
| diagnostics | /diagnostics | Collect diagnostic evidence |
| choose | /diagnostics | Choose a diagnostic task |
| lesson | /diagnostics | Current assigned exercise, title/code/version |
| result | /diagnostics | Review completed result, not an invented next lesson |

The home also has genuine secondary links. Instrument-related cards only appear when a guitar has been configured. A real task identity appears only when returned by the existing services. The page does not fabricate progress percentages, streaks, minutes played, scores or AI feedback.

## Error and race behavior
- Loading displays a status rather than a premature link or invented empty state.
- Errors show an explicit alert and manual retry; no misleading primary CTA or secondary navigation appears in this state.
- Only a 404 on selected diagnostic-session GET invalidates the corresponding browser selection, guarded against concurrent selection changes. Other failures preserve it.
- Stale responses after learner/account change or unmount are ignored using generation and actor identity checks; a new account triggers fresh restore.

## Verification and owner review
```shell
cd frontend
npm ci
npm run build
npm test
npm run test:intake
npm run test:tuner
```
Run with flag unset/false and true. For visual review, enable flag before launching the Vite dev server. Validate RU and EN, 1440/1180/768/360 viewport widths, 200% zoom, keyboard focus, default/no guitar, new user, active assignment and completed assignment. Refresh and verify no POST from Home. Test failure + retry and slow account switch with realistic fixtures; local screenshot-only checks do not prove real API behavior.

CI's generic Chrome smoke exercises other app surfaces and is not the same as manually approving the new Home in both languages. The owner decides the visual result. Do not merge PR or close #149 before that acceptance.
