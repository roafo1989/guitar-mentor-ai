# TOOL-01 / #98 — Guitar tuner

MVP: standard E2 A2 D3 G3 B3 E4 (A4=440 Hz). Selected string is explicit. Browser-only Web Audio, no backend, recording or uploads. Pitch detector searches 70–375 Hz and requires RMS >=0.01, normalized difference <0.16, and three stable readings spaced ~120 ms within 25 cents. “In tune” = selected string within ±5 cents, after stabilization. Alternative tunings/calibration excluded.

One microphone owner: an attempt recording can stop the tuner, but tuner cannot stop a recording; a draft is never erased. TOOL-01 dispatches guitar-mentor:tool-activated (detail.tool='tuner') for #87 metronome integration after #87 is implemented. No code for #87 currently in main. Stop, Close, Escape, route change and component unmount release the microphone. Late permission approvals after closing are stopped. Use HTTPS or localhost for getUserMedia; a custom HTTP hosts alias may not count as a secure context.

Automatic verification: cd frontend; npm ci; npm run test:tuner; npm run build. Synthetic E2–E4 tones at 44.1 and 48 kHz, silence, noise, weak signals, note math, stability, and exclusive microphone lease. The CI workflow runs these tests.

Manual QA: open from header without losing unsaved forms; check six strings against reference tuner; mute/noise; deny/grant mic permission; stop/close/Escape; route changes; mobile; Tab/Shift+Tab; RU/EN; microphone recording conflict; verify no media upload. Real-guitar accuracy and mobile permissions require owner test. No database migrations.

## Review corrections (PR #99)
- Stable recognized notes remain visible even when the selected target is more than 200 cents away; the status then says wrong string and can never be green.
- The ±50-cent meter clamps its accessible numeric value and needle, while visible text and aria-valuetext retain the full cents deviation. An unavailable reading has no meter role/value.
- A newly opened modal clears old microphone errors. The Teleported overlay sets the background app shell inert, has document-level focus isolation and restores its previous state on close; the header restores focus to the launcher.
- Regression tests cover wrong-target notes, cents ±50/±1200, no-reading semantics and focus isolation (using mocked focus events); manual browser verification is still necessary.

## Octave alias fix after second review

Instead of returning the first low YIN normalized-difference valley, the detector evaluates all valid local minima and chooses the earliest period close to the global best. This mitigates the second-harmonic 2x pitch error on E2/A2/D3 and prevents false subharmonics on pure sine waves. Regression tests cover all six strings at 44.1/48 kHz, strong second/third harmonics, slight decay/noise, and detunings. Ambiguous audio still requires on-instrument validation.
