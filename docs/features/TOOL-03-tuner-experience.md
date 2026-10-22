# TOOL-03 / #108 — Guitar tuner interface and alternate tunings

## Scope and interaction

This increment redesigns the tuner using an **original inline SVG 3+3 headstock**.
The six keys are real semantic buttons (keyboard/touch), not graphical-only
hotspots; they always refer to physical strings 6 through 1. Left: bottom to top
6, 5, 4. Right: top to bottom 3, 2, 1. Buttons, string paths and their targets
update together. Tap a key for Manual; Auto resets the selection and acquires
three fresh, reliable frames before assigning the nearest open string.

All instructions/errors/statuses are translated in RU/EN. Warm highlight means
a string is *selected*; only a fresh reading within ±5 cents turns it green.
Held/ambiguous/silent input never counts as tuned. Meter bounds remain ±50 cents,
while the text retains the full deviation. No automatic peg-rotation advice.

## Tuning profiles

| ID | 6 | 5 | 4 | 3 | 2 | 1 |
| --- | --- | --- | --- | --- | --- | --- |
| STANDARD | E2 | A2 | D3 | G3 | B3 | E4 |
| DROP_D | D2 | A2 | D3 | G3 | B3 | E4 |
| OPEN_G | D2 | G2 | D3 | G3 | B3 | D4 |

Notes have explicit octaves. All targets use equal temperament (A4=440 Hz);
the old Standard constants are preserved. Profile selection affects the
target frequency, cents calculation, and Auto string classification — not merely
the UI label. To preserve octave fidelity, Auto considers **absolute cents**
between the detected fundamental and each target, not pitch-class equivalence.
Low-D headroom is expanded to **60 Hz** in both YIN and the FFT validator,
without relaxing their confidence/ambiguity thresholds. The existing 8192-sample
analyser and 100-ms cadence are retained.

Changing a profile resets pitch history, the held sample, the meter and any
green status. Auto target resets; Manual retains its physical string number but
receives the new target and requires a new measurement. The running mic stream
is not reacquired. Since WebAudio frame evaluation is synchronous and a profile
change happens on the main thread, no prior-profile callback can publish after
the reset; generation guards still cover late permission grants/closing.

The preference is restricted to a versioned local JSON object under
`guitar-mentor.tuner.preferences.v1` containing `schemaVersion: 1` and
`tuningId`. Unknown/corrupt data falls back to Standard; storage errors are
non-fatal. Microphone state, audio, permissions and pitch history are not
persisted. Reopening or refreshing does **not** automatically start the mic.

## Regression and manual QA

Run:
```bash
cd frontend
npm ci
npm run test:intake
npm test
npm run test:tuner
npm run build
```
New tests exercise all 18 target-note positions across the three profiles,
D2/G2/D4 at 44.1/48 kHz, D2 detuning, octave-separated Open G targets,
preference validation and live Vue headstock/selection interaction. Existing
#98 pitch, hold, ambiguity, recorder arbitration, backdrop/escape/focus and
metronome regression tests remain active.

**Manual QA still required:** Desktop and mobile (including 360 CSS px), real
microphone on acoustic guitar, switching presets while sounding and while
permission is pending, focus trapping with keyboard including the native
select, interrupted microphone, profile restoration without autoplay,
recorder-priority and the metronome coexistence. Check Standard E2, Drop D D2,
Open G G2 and D4 with an independent reference tuner.

No backend, external SDK, streaming or file uploads. The supplied screenshots
are not redistributed; headstock artwork is repo-authored SVG/CSS.

## Review remediation — 21 Oct 2026 (PR #110)

- **P1 (stale green, fixed):** the three-frame median remains the note
  confirmation and smoothing tracker. A fresh, reliable candidate is shown
  while the median trails a continuously moving pitch by more than ±5 cents.
  The Vue component only confirms `inTune` if **both** the current candidate
  and the confirmed three-frame median are inside the target's ±5-cent
  tolerance. A transient candidate crossing a tuned target while the median
  is still detuned shows stabilization instead of a false green; the bounded
  700-ms hold remains explicitly labeled and never confirms tuning.
- **P2 (acoustic testing, added):** alternative presets have harmonically rich,
  decaying and inharmonic synthetic profiles, 44.1/48 kHz, ±25 cents,
  weak fundamentals and observable two-string competition; ambiguous audio
  may return no measurement, never a confident wrong octave/target.
  One regression reproduces the exact +20/-20 cents P1 sequence; another
  uses a real compiled Vue shell with a mocked Web Audio stream to verify
  live Standard→Drop D→Open G changes without permission/stream duplication,
  stale green, and microphone autoplay. Real-guitar QA remains NOT_RUN.
- **P2 (design preview, prepared for owner):** see
  [six visual states](../ux/TOOL-03-tuner-state-preview.svg),
  [360px mobile proposal](../ux/TOOL-03-tuner-mobile-preview.svg)
  and [preview notes](../ux/TOOL-03-visual-preview.md).
  These are static illustrations, not screenshots of a browser execution;
  the owner must still confirm visual acceptance of the running frontend.
- The branch was merged with current `main` as a two-parent history-preserving
  commit before final CI; no source files overlapped in that main update.
  This preserves other in-progress work without rewriting history.

**Remaining gates:** independent code review on the new HEAD, live browser/mobile
and acoustic QA, owner approval of the visual design. Do not merge PR #110
without explicit owner acceptance.


## Follow-up P1: continuous turning of the tuning peg — fixed 21 Oct 2026

The previous safeguard mistakenly treated a valid pitch changing by 6 cents
per ~100 ms as no signal. That repeatedly cleared the Auto-selected physical
string, note and meter while the musician was actively adjusting the peg.

**New behavior:** Once a string's pitch is established (three consistent
frames), the latest reliable candidate continues to drive the live needle
and detected note while the median catches up. A pitch jump indicating a new
string still resets tracking and requires three fresh frames before selecting
the next target. `inTune` stays conservative: **current candidate AND
three-frame median must both be within ±5 cents of the selected target**.
A brief crossing from an out-of-tune pitch into the target does not turn
green without stable confirmation.

**Regression tests:** ten successive +6c/100-ms increments and ten −6c/100-ms
increments on A2 check that the pitch, needle and physical fifth-string peg
stay visible in Auto, with correct sharp/flat status and no green.
Additional unit and mounted Vue tests cover initial acquisition, +4→+8c,
±20c, a momentary return to target, and A2→D3 requiring fresh confirmation.
The waveform path is exercised via a mocked Web Audio analyser;
real-device acoustic and mobile UX acceptance remain pending.

**Verification:** [GitHub Actions 39674392779](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39674392779)
passed metronome regressions, tuner DSP tests, TypeScript and Vite on
`bb2420a4889dd0f0c92cb067b7620cd4a2b3519f`.


## Strict-review P1-3 — sustained stability and continuously visible meter (22 Oct 2026)

A pitch changing by 6 cents per 100 ms can pass the target while both
the newest candidate and median are in the ±5-cent tuning band. That is
**not** a stationary tuned string. A second defect was the conditional
`reading=null`, which removed a valid needle and accessible cents exactly
when passing through the centre.

**Correct independent states:**

- **Live valid pitch:** visual note, full signed cents, direction, target
  and meter; independent of any stable/green verdict, including ±2 cents.
- **Tracked pitch / hold:** three-frame initial stabilization and bounded
  hold on missing candidates; holds are explicitly marked and never green.
- **Verified in tune:** four **consecutive fresh candidate** measurements,
  each within ±5 cents of the same physical string's target, with no more
  than **3 cents** total spread and **2 cents** net drift over the window.
  Larger drift, a single invalid frame, target/profile change or out-of-band
  measurement resets the verification window. While waiting for confirmation,
  the reading and needle remain visible, only the status says stabilizing.

Regression coverage includes symmetric target crossings
`+20→+14→+8→+2→−4→−10` and
`−20→−14→−8→−2→+4→+10`, steady 0-cent confirmation,
small ±1-cent jitter, dropped/held frames, profile changes and earlier
glissando/false-green regressions in unit and real mounted Vue-SFC tests.
The four-frame rule adds conservatism intentionally; physical acoustic QA
remains necessary before owner acceptance.


## Approved mockup implementation — 22 Oct 2026

The owner approved the frontal 3+3 headstock and compact dark tuner mockup
in this project conversation, with colours coordinated to metronome #111.
Implementation retains original SVG authored in the repository (no borrowed
brand artwork), shifts the tuning meter into a separate instrument panel,
uses a deliberately compact 506px modal and a centered 440:340-scaled
headstock panel with all six real, accessible peg buttons strictly inside
its bounds. An angled guitar headstock is explicitly **not** acceptable.

- Six string selectors are always physical numbers 6→1, with octave labels.
  Metal hardware and strings share the headstock's SVG coordinate system;
  CSS cannot independently crop a last string on narrow displays.
- Min 44×44 interactive selector size, responsive 320–360px styling,
  explicit overflow-y for short screens/zoom (never hide content), keyboard
  focus visibility and reduced motion preserved.
- Auto control is a semantic toggle button; preset is an accessible select;
  mic status is separate from acoustic pitch/green confirmation.
- DSP, 700ms hold, four fresh in-tune candidates, tuning presets, audio
  lease, recorder, focus lifecycle and metronome integration are unchanged.
- Automated component tests verify six rendered peg labels, insets and
  screen paths; live physical browser sizing and owner's visual sign-off
  of implementation remain part of final manual QA.

## Follow-up reviewer remediation — machine proportions and mobile focus margins (22 Oct 2026)

Following [review #5607524502](https://github.com/roafo1989/guitar-mentor-ai/pull/110#pullrequestreview-5607524502), the symmetric string-selector centres now sit at **x=70/370** instead of x=38/402 within a 440-unit scene. Their *full* 45px mobile touch targets plus 5px focus-outline/offset allowance remain **at least 12 CSS px inside** the stage even on 320px browser widths (scene about 282px), so `overflow:hidden` cannot clip the focus ring or touch target.

The six original oversized tuning keys/shafts have been redrawn as smaller, close-fitting, mirrored chrome machine assemblies. Outer key x bounds are **128..312** relative to the head's x bounds **154..286**, giving total hardware-to-wood width ratio **184/132 = 1.39** (previously 246/132 = 1.86). The winding posts, string paths, numbered physical selectors and play-state behavior are unchanged.

A mounted *real Vue SFC* test now calculates full touch-target + focus-ring bounds at responsive scene widths **282, 300, 322, 440px** and verifies 12px minimum inset, 3+3 key/shaft counts, symmetric hardware positions and restrained wood-to-metal proportions. This is geometry regression coverage, **not a real browser screenshot**. Final owner visual verification in a live 320/360px browser is still required before merge.
