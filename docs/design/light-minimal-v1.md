# Guitar Mentor AI — Light Minimal UI/UX v1.0
Status: APPROVED CONCEPT (2026-10-24). Implementation is NOT approved as completed.
Design direction: option 01 — light minimalism, approved by product owner. Dark and hybrid alternatives rejected for this iteration.

## Product principle
The interface must help the student **play music**, not operate software. Follow an Apple-inspired approach to clarity and restraint without copying Apple assets, branding, or layouts.

## Reference composition (concept, not pixel-accurate source)
The approved concept depicts a full-width desktop lesson workspace:
- White sticky top header with Guitar Mentor AI identity, Home / Learning / Library / Tools / Profile navigation, quick access to tuner and metronome, learner identity.
- Light off-white page background.
- Compact 'Current lesson' header with exercise identity, progression and sample-audio player.
- Main two-column workspace: **primary wide music score / tablature and PDF viewer** on the left, **practice controls** on the right.
- Right rail in meaningful order: recording/submission, metronome, tuner, AI feedback, progress.
- Green accent, subdued borders, restrained shadows, ample spacing, accessible typography.
- A distraction-free fullscreen score mode.

The image is a *visual direction*, not implementation truth. Its exercise name, percent, scores, AI advice, user identity, minutes, BPM, buttons, artwork and scores are dummy example data. Never hard-code these.

## Recommended initial design tokens (provisional — verify visually and accessibly)
- background #F7F8F7; surface #FFFFFF; text #162331; secondary #62717D
- brand/primary #28776D; primary-hover #205F57; outline #DDE5E4
- focus ring visible with 3:1 contrast against adjacent surfaces
- font system stack: Inter, Segoe UI, system-ui, sans-serif
- spacing scale 4/8/12/16/24/32; corner radius 8/12/16
- minimum target size 44x44px for prominent interactive controls where possible
- no ornamental animation; reduced-motion support
Final colors require WCAG AA checks (normal text >=4.5:1, large text >=3:1).

## Screen/behavior requirements
1. Shell: sticky header on desktop, compact usable responsive header on small devices. Username/learner state, working navigation and accessible tool controls. Existing routes must continue to work; do not show non-existing destinations as active routes.
2. Homepage: clear next-step CTA driven by existing learner/diagnostic state (profile -> onboarding -> diagnostic/current work), no fabricated progress. Secondary cards for appropriate available features only.
3. Lesson/diagnostic workspace: prioritize **correct standard score + tablature PDF**. Preserve the user's approved example format `ex2.pdf`: musical staff + rhythmic durations and correctly placed pitches, aligned TAB, four measures per system where the export format allows. The generated concept image is NOT musical notation reference and MUST NOT be used to reconstruct notes.
4. Sample audio: independently previewable, proper player states, keyboard controls; do not confuse reference audio with learner submission.
5. Recording: distinguish start/stop, preview, replace and submit; prevent accidental loss of data; preserve current recording/business logic and navigation guards.
6. Metronome: reuse existing metronome behavior, including multiple time signatures; do not arbitrarily force 4/4. Tuner uses existing tool and microphone permissions; never claim both mic tools can operate concurrently if audio conflicts.
7. AI guidance and progress: display only backend-supported data; use clear 'not available yet' / future-state treatment instead of fictional evaluations, streaks or levels.
8. Score reader: zoom, pagination, keyboard/accessibility, fullscreen/distraction-free mode; keep score readable on mobile and in landscape. Native full-screen capability may need browser fallbacks.
9. Loading, empty, errors, disabled, submission and success states explicitly designed.
10. i18n: maintain Russian and English; preserve keyboard support, semantic labels and visible focus.
11. Performance: no large decorative assets blocking the first useful interaction; responsive breakpoints should be based on content.
12. App architecture: inspect existing Vue 3 components, shared CSS and routes before changes; preserve existing business logic and tests. Prefer reusable components and CSS tokens; avoid page-specific conflicting global styles.

## Work decomposition and suggested implementation order
A. Shared tokens, shell and navigation.
B. Homepage.
C. Lesson/diagnostics work area and score viewer.
D. Audio/recording + metronome/tuner control integration.
E. AI feedback/progress states and responsive/a11y polish.
Do not merge these in a single unreviewed rewrite. Keep current project issue workflow: Analysis -> Estimate -> Development -> Testing -> Completed; story has Backlog -> Development -> Completed. Status must reflect reality.

## Acceptance criteria
- User can start or resume an actual available lesson path from the home screen.
- At desktop widths music notation gets majority of workspace; recording and tools remain visible but subordinate.
- At phone widths there is no horizontal page overflow; score may scroll/zoom deliberately within its own container.
- Standard PDF/TAB correctness is verified from real content, never from illustrative artwork.
- Existing profile, onboarding, diagnostic submission, metronome and tuner flows pass regression tests.
- At least keyboard-only smoke-test, narrow desktop/tablet/mobile viewport checks and RU/EN long-label checks.
- No dummy stats or AI recommendations presented as real.
- Visual approval by the product owner before completion.

## Scope boundaries
This is a UI/UX redesign, not a request to silently ship missing catalogue, learning or AI functionality. Create dependencies for missing features separately. No changes to production code are included in this design documentation commit.

