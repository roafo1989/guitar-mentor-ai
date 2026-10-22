# TOOL-04 · Issue #111 — Rhythm Studio B1 (Graphite / Mint)

**Date:** 2026-10-22  
**Decision:** owner approved the visual concept B1 and expressly authorized development.  
**Source:** [Issue #111](https://github.com/roafo1989/guitar-mentor-ai/issues/111) and [US-010 #112](https://github.com/roafo1989/guitar-mentor-ai/issues/112).  
**Implementation branch:** `feature/issue-111-rhythm-studio-b`.  
**Product PR:** [#117](https://github.com/roafo1989/guitar-mentor-ai/pull/117).

## Intent and exclusions

Convert the already accepted #104 full-feature metronome to the owner-approved B1 dark Graphite/Mint quick-use layout **without changing its audio engine** or accepted lifecycle contracts. Only one built-in appearance in #111; selectable A/C skins belong to [#112–116](https://github.com/roafo1989/guitar-mentor-ai/issues/112).

The visual acceptance baseline is the **Rhythm Studio B v3 prototype**, approved 2026-10-22. The prototype itself is not a production implementation and does not count as browser QA.

## UI structure

- `frontend/src/entities/metronome/MetronomeDialog.vue`: existing reactive config state and Web Audio commands retained; a new `panel` state presents **Rhythm & Sound** or **Presets** in an overlay, without creating another audio engine or another modal.
- `frontend/src/assets/main.css`: scoped `.m111` overrides, with explicit B1 palette: background `#10191f`, panel `#18262e`, controls `#20313a`, accent `#99dfc9`, primary text `#f0f7f3`. Breakpoints for compact 360-pixel screens and shorter landscape/mobile viewports.
- `frontend/src/i18n/{ru,en}.json`: manual BPM, sheet control, accent hint, subdivision labels, and preset preview.
- `frontend/tests/metronome-dialog.component.test.mjs`: mounted tests for quick-use and settings/preset panels, added to existing #104 regressions.

## UX contract

1. Directly editable, labelled BPM `20..240`, with physically separated ± and visible Tap. Clicking the actual numeric input opens an appropriate numeric keyboard on mobile. Invalid text keeps the last valid audio BPM until corrected.
2. Five quick meters (2/4, 3/4, 4/4, 6/8, 7/8) on the first screen; other quick meter presets and arbitrary N/D 1..16 / 2,4,8,16 in Rhythm & Sound.
3. Every beat shows its number and symbolic accent (● strong, ◐ medium, ○ weak, × mute) with accessible localized labels. 6/8=3+3 and 7/8=2+2+3 are visible through the grouping label. For 8–16 positions, the beat strip scrolls **internally horizontally**; the dialog itself does not overflow screen width.
4. Subdivision 1–4 is available on the first screen. Underlying `draft`, active/queued settings, per-position volume, master, accent gain and subdivision gain all reuse existing #104 handlers.
5. Apply/Discard remain available for compound changes; only accepted configuration sounds. The currently sounding meter and the draft meter have separate visual grids while pending. Audio-only BPM remains immediate, compound changes retain bar-line synchronization.
6. Built-in preset CRUD (up to 20), recovery and errors remain in the Presets panel. A preview line before Save/Replace summarizes BPM, meter, unit and grouping; the full config including levels, accents and subdivision is still saved.
7. Start/Stop remains the same DOM button during status transitions; minimize never invokes Stop. Clicking the backdrop does not close the dialog; explicit ×/Escape continues to close and stop via App controller. When an overlay is open, Escape first closes it; a subsequent Escape closes the metronome.
8. The original #104 behavior on page/visibility/tuner/recording remains unchanged. Background-tab playback enhancements are deliberately **out of scope** (Issue #109).

## Verification and manual QA checklist

Automated: `cd frontend && npm ci && npm run test && npm run test:tuner && npm run test:intake && npm run build`; GitHub workflow `.github/workflows/frontend-ci.yml`.

Suggested manual checks in Chrome/Windows and mobile viewport:
- Open from header and from lesson card. With 360×640 CSS px, verify BPM/±/Tap, 4/4, accent strip, Start, presets and advanced entry **without forced vertical scrolling**; retry zoom 200%, screen-reader focus, RU/EN strings.
- Change 80→90 BPM by input/±/Tap; enter 241/blank and verify visible error/no start. Tab/Shift+Tab around dialog; Enter and Escape in and out of settings.
- Start 4/4, switch draft to 6/8 while playing, verify active/preview separation, Apply, next bar change without duplicate sound. Repeat 7/8 2+2+3 and custom grouping 3+2+2.
- Test all 16/16 positions via horizontal strip, master and individual position volume, all accent types including beat one and mute.
- Save/Load/Rename/Replace/Delete/20-limit, invalid name/group, blocked/corrupt localStorage, F5 re-open without autoplay.
- Minimize while recording to verify sound continues; close ×/Escape stops; backdrop click does not; tuner interlock stops audio; switching tabs retains current #104 behavior, **not** speculative #109 behavior.

## Release process

Do **not merge #117 until** tests/CI, independent code review and explicit owner acceptance are complete. Test from the PR branch, not the main branch:

```powershell
git fetch origin
git switch feature/issue-111-rhythm-studio-b
git pull --ff-only origin feature/issue-111-rhythm-studio-b
cd frontend
npm ci
npm run dev
```

The GitHub Project v2 stage and status require separate synchronization in the Project UI: connected GitHub operations cannot modify those fields.
