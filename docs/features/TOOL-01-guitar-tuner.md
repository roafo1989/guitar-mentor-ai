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

## QA BUG-98-QA-01: suppressing harmonic aliases

The first plausible YIN period is checked against a Hann-windowed 8192-point FFT. A proposed fundamental must have measured energy and harmonically consistent peaks, and its time-domain period must also pass normalized-difference validation. Unsupported or contradictory estimates are withheld (`null`), rather than shown as confidently tuned. Automated regression tests cover 108 resolvable cases (6 guitar strings × 2 sample rates × 9 spectra with a present fundamental), including fundamentals just 2% of an overtone.

**Physical limitation:** a pure higher harmonic with no fundamental energy cannot be distinguished from a real higher note solely from its waveform. The detector does not invent an inaudible lower fundamental. Real guitar recordings, permissions, keyboard and mobile QA remain mandatory.

## Independent review P2: stiff-string inharmonicity

A detected harmonic series establishes the **octave** but its upper partials are not assumed exact multiples of the fundamental. Before this correction the FFT estimator averaged `peak(h) / h`, systematically raising the indicated tuning by +5.46…+6.33 cents at B=0.001 for six correctly tuned strings. The detector now returns the independently measured **fundamental FFT peak** for pitch accuracy; higher partials only select and corroborate the fundamental. The YIN normalized-difference check is retained to reject an inconsistent or ambiguous periodicity.

Regression coverage: 288 cases (6 strings × 2 sample rates × 2 spectra × 4 stiffness factors × 3 tuning offsets), asserting error under 3 cents and UI `inTune` at true pitch and `flat/sharp` when detuned. Another 144 weak-fundamental cases enforce no confidently wrong note (an ambiguous frame may return null). Tests remain synthetic; real microphone and acoustic-guitar QA is still required.

## Real-guitar QA follow-up: sustained detection and automatic string selection (21 Oct 2026)

The owner tuned a genuinely detuned acoustic guitar but found two practical defects:
- **REAL-01:** The displayed note disappeared shortly after the pluck, requiring repeated attacks.
- **REAL-02:** Every string required manual selection. The requested default is automatic recognition, similar in workflow (not copied UI) to GuitarTuna.

Resolution: lower the initial absolute signal RMS gate from 0.01 to 0.003 while retaining YIN and FFT confidence checks; analyze every ~100 ms; use three consistent measurements to acquire a note and hold its last **verified** value for at most 700 ms through occasional null frames. A different valid pitch immediately clears the old reading and must stabilize independently. The grace period is **not** renewed by null frames, and a held reading is visibly identified as such (not colored «in tune»). Once expired, silence clears it. Test against real decay, microphone noise and changes of string — synthetic tests do not certify all hardware.

On opening the dialog **Auto** mode is enabled. After pitch stabilization the nearest target among E2/A2/D3/G3/B3/E4 is selected within ±175 cents for the first selection; a previously confirmed target can persist within ±235 cents and 70-cent hysteresis prevents flicker between adjacent string targets. Ambiguous/out-of-range frequencies leave the target unselected, and the recognized note can still be shown without an incorrect green status. Clicking an individual string activates a manual override; clicking Auto returns to automatic selection. Strum/chords and severely detuned strings require manual mode when the automatic classifier is not confident. RU/EN labels and ARIA states updated.

**Manual acceptance:** check six strings with soft pluck and decay, delayed silence timeout, all six automatic target changes, manual fallback, strong detuning, background noise, chords, recorder protection and mobile/keyboard interactions. Do not treat these browser checks as passed until observed.
