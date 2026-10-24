# FT-016-01 / Issue #148 — Light Minimal shell

## Build toggle and rollback

UI is opt-in: set `VITE_GUITAR_MENTOR_LIGHT_MINIMAL=true` in the frontend Vite build environment (not a Java backend variable), then restart/rebuild Vite. Exact string `true` is required. Missing or any other value leaves the existing header/layout intact. Rollback: remove the variable or set `false` and rebuild. No API/database changes or stored user data are touched.

## What changed

- Semantic `--gm-*` tokens in `frontend/src/assets/design-tokens.css` are scoped under `data-ui="light-minimal"`.
- `frontend/src/assets/light-minimal.css` handles shared surfaces, buttons, inputs, header, reduced motion and responsive navigation; it is gated under the same root attribute.
- Existing `App.vue` remains the single owner of logout, actor state, tool dialogs and route change cleanup.
- Header links: Home `/`, Profile `/profile`, Experience `/onboarding`, Practice `/diagnostics`. No invented library route.
- Below 1181px the main navigation becomes a disclosure controlled by a button. Escape closes the disclosure and returns focus. Routes close the disclosure. On desktop the links stay visible.
- Auth views, profile and onboarding share surface/input/button tokens. Existing Google and email login flows are not replaced.

## Verification and acceptance

On the PR HEAD run `cd frontend && npm ci && npm run build && npm test && npm run test:intake && npm run test:tuner`. Verify with both build-flag states; re-run CI when changing the code.

Manual owner checks in Chrome/Edge, preferably 1440, 1180, 768 and 360px plus 200% zoom:
1. Enable flag before running `npm run dev`, sign in with existing email account (and Google if available), switch RU/EN; confirm appearance remains light.
2. Open Profile, Experience and Diagnostics; scroll downward and click logo to return Home. Header stays visible and no content is occluded.
3. Resize below 1181px. Open menu, press Escape and confirm focus returns to menu button. Open again, use Tab and select a destination. No broken `/library` link.
4. Verify a long display name is truncated only visually (hover tooltip exposes the full name), and both tools, locale and sign-out remain accessible on narrow widths.
5. Open tuner/metronome and close each via its own existing controls; tools should preserve focus and any recording session should not be modified.
6. Revisit registration/verification/reset/settings and logout pending/error states without exposing secrets or changing auth permissions.
7. Rebuild with flag disabled and confirm the legacy header returns; no backend or saved state should change.

Record actual browser/OS/viewport, PASS/FAIL and any screenshots in #148. A visual sign-off belongs to the project owner; a successful unit/CI run is not proof of microphone behavior, keyboard usability, pixel contrast or Google OAuth.

## Instrument icon assets — owner's visual selection (2026-10-24)

The owner uploaded transparent PNG source files `free-icon-tuner-8031641.png` and `free-icon-metronome-3564896 (1).png`. Their silhouettes were converted to small local vector paths (no external URLs or runtime tracking) and are included as `frontend/public/icons/tuner-flaticon.svg` and `frontend/public/icons/metronome-flaticon.svg`. Light Minimal uses these; legacy remains unchanged.

The Flaticon source credit links in the application's footer point to icon IDs 8031641 and 3564896. Note the tuner upload says **8031641**, while the previously suggested URL said **8031645**. The supplied file, rather than the earlier differing URL, is the visual source of truth. Free Flaticon assets normally require creator attribution, and creator identity could not be independently verified here. **Before a public release, verify actual uploader's icon credits and license details and replace generic Flaticon links with the required author-credit links or obtain a paid license.**
