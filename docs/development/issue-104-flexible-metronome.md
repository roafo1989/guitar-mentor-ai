# #104 / TOOL-02 — Flexible metronome implementation

## Change register

| Revision | Date | Description |
| --- | --- | --- |
| 1.0 | 2026-10-21 | Implements TOOL-02 v1.1 against accepted #86/#87; QA checklist |

Source: [specification TOOL-02 v1.1](https://github.com/roafo1989/guitar-mentor-ai/blob/analysis/system-analysis-standard/docs/specifications/TOOL-02-flexible-metronome.md) (analysis branch PR #64 if not yet merged). Development PR #105. Parent #57 / epic #53.

## Architecture

- **Engine:** frontend/src/shared/audio/metronome.ts — one Web Audio AudioContext and master GainNode, shared by header and exercise view. Scheduler uses AudioContext.currentTime with a 25 ms look-ahead and 100 ms audio scheduling horizon; no DOM-based sound clock. Lifecycle/stop behavior from #86/#87 retained.
- **Pure model:** frontend/src/shared/audio/metronome-config.ts — meter, subdivisions, groups, tempo unit, accents, output gain validation, Tap Tempo, strict local persistence. No network, microphone, server or database changes.
- **Controller:** frontend/src/entities/metronome/metronome-ui.ts — restores a valid local config exactly once when the singleton is first imported. Never restores running state or starts sound. Opening an exercise while running/starting never reapplies tempo.
- **Controls:** frontend/src/entities/metronome/MetronomeDialog.vue — BPM ±1, typed BPM and Tap; numerators 1..16 and D=2/4/8/16; quick time signatures; per-beat accent buttons; advanced settings for groups, displayed BPM unit, subdivisions, independent levels and 20 named local presets.

## Musical contract

An N/D bar has **N denominator-note positions**. A BPM unit is explicitly chosen as quarter, eighth or dotted quarter.

  positionSeconds = (60 / bpm) * (4 / denominator) / quarterEquivalent

where quarterEquivalent is 1, 0.5, or 1.5. Sub-ticks are exactly positionSeconds / subdivision. Position 1 is not forcibly strong; users may mute or reaccent any position. A mute/zero position suppresses its sub-ticks too, without changing the clock. No stacked oscillators at the first sub-tick: the main click is the first subdivision.

When running, a **standalone BPM change** restarts at beat one immediately, retaining an already requested composite pattern for the next complete bar. Composite meter/unit/groups/accents/preset changes swap atomically at the bar line. Master volume is immediate and changes only the app Audio GainNode. Stop cancels scheduled sources and visual timers; pending config becomes the stopped config. Resume after pagehide/hidden is never automatic. 6/8 dotted-quarter=60 has a 2-second bar, 7/8 eighth=140 has a 3-second bar.

## Storage and privacy

Key: guitar-mentor.metronome.v1. JSON v1 contains only currentConfig and local presets (id, name, config). Do not serialize state/AudioContext/timers/material/user recordings. At most 20 presets, name 1..60, encoded JSON <=64 KiB. Refuse malformed/unknown/oversized JSON without overwriting it automatically. Failures of localStorage are non-fatal; the metronome works in memory and shows a localized storage warning. Explicit reset of corrupted storage requires user confirmation.

Presets are scoped to the current browser profile and origin; not synchronized across devices. Material BPM is never written back to the exercise.

## Development verification

Run in /frontend with Node 22:

    npm ci
    npm test
    npm run test:intake
    npm run build

Metronome tests include 100 simulated bars per supported representative meter (4/4, 3/4, 6/8, 7/8, 16/16 with subdivision4), boundary timing calculations <=1e-6 seconds in a **fake** AudioContext, mute/subdivision checks, pending/BPM races, lifecycle, storage corruption/quota, and existing mounted UI/recording/shell regressions. The 1e-6 tolerance is a computational invariant of mocked scheduling, **not** an audible jitter SLA.

## Owner/manual QA still required

1. Windows 10 Chrome and Edge: open via header, play/stop 4/4; 6/8 dotted quarter at 60 (two groups); 7/8 switch 2+2+3 to 3+2+2; edit the first accent to weak, mute another beat; adjust master, levels and subdivisions.
2. Enter BPM90, then invalid241 and empty; use ±1, then Tap four evenly spaced taps. Test keyboard Tab/Shift+Tab/Escape, touch 360px and RU/EN. Note actual versions.
3. While playing: edit meter and apply; verify exactly one bar-line transition, no double click; change BPM separately and verify immediate beat-one reset. Start while Web Audio resume is slow; Stop then retry.
4. Save a preset; reload browser (F5) and confirm controls/config persist but audio remains stopped. Load, rename, replace with confirmation, delete with confirmation. Restrict storage and verify nonfatal failure.
5. Play → minimize → reopen via header or exercise; accidental backdrop click must not stop playback; both orderings of starting recording and metronome must work, with microphone-only MediaRecorder and headphone guidance. Explicit ×/Escape/Stop, route/material change, pagehide/visibility hidden and tuner interlock must stop playback.
6. Foreground real-audio checks: continuous 4/4 and 6/8 for 5 minutes each; record browser, OS, device, AudioContext sampleRate, audible drift/clicks and regression vs #87. Test Android Chrome and iPhone Safari if accessible; missing hardware is **BLOCKED**, not PASS.

## Deployment, rollback

Frontend-only build, no new env variables, permissions, backend schema or services. Keep owner acceptance before merge. If transitions/double sounds/lifecycle fail, stop and redeploy the frontend at the merged #87 commit 88bc39e; refresh and smoke-test 4/4/Start/Stop/minimize/recording. The v1 storage key is ignored by the older frontend, so deleting it is unnecessary.
