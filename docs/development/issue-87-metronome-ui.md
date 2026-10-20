# #87 — Metronome: header tool and exercise preview (implementation)

Reuses the **single** `metronome` exported by `frontend/src/shared/audio/metronome.ts` from completed #86 (PR #102); no extra Web Audio context/loop, backend, storage or microphone.

## UX / contract

- All pages: metronome icon in sticky header opens a keyboard-operable modal without navigation. Header opens at last selected tempo (default 80 BPM), without autoplay.
- Exercise preview and assigned task: «Metronome · N BPM» opens the same singleton modal. The exercise's *recommended* BPM is applied only if the engine is stopped; while already playing or starting, clicking any exercise entry reopens controls without stopping, resetting the beat scheduler or overwriting the user's current BPM. Neither the catalog nor the assigned snapshot is modified.
- Numeric BPM: integer 20…240, ±1 buttons and numeric field, input errors are shown without changing the last valid engine tempo. Four visible beats with first beat accented; sound only on explicit Start, Stop available and initial beat comes from #86.
- Escape and the explicit × close button stop playback. Clicking the dark backdrop **does nothing**: accidental clicks never close the modal or silence practice. **Minimize and keep playing** hides the modal but leaves playback active. The header icon visibly indicates playback. To stop minimized playback, reopen the controls and select Stop or close the modal. Focus trapped while modal is open, restored to the opener (or header tool if the exercise disappears). Overflow of the underlying document is suppressed.
- Leaving a route or changing/removing selected material stops playback and closes modal; hidden tab/F5/pagehide handled by #86. Opening an exercise while stopped applies its recommended BPM, but reopening via an exercise while running or starting leaves the current BPM and audio unchanged. Microphone recording and metronome playback can run concurrently by explicit product-owner request (21.10.2026). After Start, choose **Minimize and keep playing** to return to the exercise and begin recording; the header stays visibly marked as playing, and reopening it shows Stop. Alternatively start recording first, then open the metronome and minimize. Neither starting nor ending recording stops the metronome.
- #98 tuner remains a separate issue. It must call `stopMetronomeForAudioCapture()` or `metronome.stop()` on activation.
- Only the microphone MediaStream is passed to MediaRecorder: no direct internal mix of Web Audio clicks. Speakers can bleed acoustically into the microphone (headphones recommended); no acoustic cancellation or guaranteed click-free recording is promised.
- The modal isolates the **entire page shell** using native `inert` (the dialog is teleported directly to `body`, outside the inert shell); background focus/assistive-tech navigation is blocked while open and restored on close/minimize. The stable Start/Stop button never becomes natively disabled during asynchronous `starting`, so the focused button stays in the tab sequence; duplicate clicks are ignored. Minimizing makes the background usable again.
- No automatic sync to preview MP3 and no analysis/assessment of the learner's performance. RU and EN labels.
- No change to the navigation and intake guard for unsaved audio/form data; modal does not navigate.

## Checks

Run inside `frontend/`:

~~~powershell
npm ci
npm test
npm run test:intake
npm run build
~~~

New deterministic `metronome-ui.test.mjs` cases cover input boundaries/rejection and ±1 clamp; #86 suite covers scheduling/one engine, interruptions and cleanup. Manual Chrome + keyboard/mobile width checks are still required before acceptance: header and assigned/catalog entry points, initial 80 BPM, exercise BPM, 20/84/240, tempo change mid-run, 4/4 accent, input errors, focus/Tab/Shift+Tab/Escape, route changes, F5, visibility change, minimize→recording while sound continues, recording→start metronome, speaker/headphone click bleed, and popup/backdrop interaction. Verify a running minimized 90 BPM metronome reopened via the exercise's 84 BPM button continues at 90 BPM without an extra Stop or beat reset. This is not an automated claim of audible timing accuracy.

## Rollback

Revert the #87 PR. Engine #86 remains intact. No database migrations or external files are created.
