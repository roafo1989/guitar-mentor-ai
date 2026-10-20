# #87 — Metronome: header tool and exercise preview (implementation)

Reuses the **single** \`metronome\` exported by \`frontend/src/shared/audio/metronome.ts\` from completed #86 (PR #102); no extra Web Audio context/loop, backend, storage or microphone.

## UX / contract

- All pages: metronome icon in sticky header opens a keyboard-operable modal without navigation. Header opens at last selected tempo (default 80 BPM), without autoplay.
- Exercise preview and assigned task: «Metronome · N BPM» opens that same modal with a copy of exercise's *recommended* BPM; neither the API catalog nor the assigned snapshot is modified.
- Numeric BPM: integer 20…240, ±1 buttons and numeric field, input errors are shown without changing the last valid engine tempo. Four visible beats with first beat accented; sound only on explicit Start, Stop available and initial beat comes from #86.
- Escape, close button and backdrop close the modal and stop sound. Focus trapped while modal is open, restored to the opener (or header tool if the exercise disappears). Overflow of the underlying document is suppressed.
- Leaving a route or changing/removing selected material stops playback and closes modal; hidden tab/F5/pagehide handled by #86. Opening a different exercise stops current sound before applying its BPM. Starting microphone recording explicitly stops the metronome first; no automatic resumption.
- #98 tuner remains a separate issue. It must call \`stopMetronomeForAudioCapture()\` or \`metronome.stop()\` on activation.
- No automatic sync to preview MP3 and no analysis/assessment of the learner's performance. RU and EN labels.
- No change to the navigation and intake guard for unsaved audio/form data; modal does not navigate.

## Checks

Run inside \`frontend/\`:

~~~powershell
npm ci
npm test
npm run test:intake
npm run build
~~~

New deterministic \`metronome-ui.test.mjs\` cases cover input boundaries/rejection and ±1 clamp; #86 suite covers scheduling/one engine, interruptions and cleanup. Manual Chrome + keyboard/mobile width checks are still required before acceptance: header and assigned/catalog entry points, initial 80 BPM, exercise BPM, 20/84/240, tempo change mid-run, 4/4 accent, input errors, focus/Tab/Shift+Tab/Escape, route changes, F5, visibility change, recording and popup interaction. This is not an automated claim of audible timing accuracy.

## Rollback

Revert the #87 PR. Engine #86 remains intact. No database migrations or external files are created.
