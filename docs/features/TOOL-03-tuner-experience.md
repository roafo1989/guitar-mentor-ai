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
