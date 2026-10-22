# TOOL-01 / #98 — Guitar tuner

## Current contract (approved on acoustic guitar, 21 Oct 2026)

**Base #98:** Standard tuning E2–A2–D3–G3–B3–E4 at A4=440 Hz. **Enhancement #108:** Standard, Drop D, Open G presets and a 3+3 headstock control (see [TOOL-03](TOOL-03-tuner-experience.md)); custom user-defined tunings and calibration remain out of scope. Browser-only Web Audio (no sound upload, backend call or migration). **Auto is the default:** after a stable single-note estimate the tuner selects the nearest open string, using confidence limits and hysteresis to avoid arbitrary switching. The musician can select a string manually; pressing Auto clears stale manual readings.

**Recognition:** YIN periodicity validated with FFT harmonic evidence, nominal pitch search 60–375 Hz (extended for detuned D2 in #108), initial RMS gate 0.003 and three compatible samples analyzed about every 100 ms. The latest *verified* pitch survives intermittent undecidable frames for no more than 700 ms; an actual new note clears previous readings, and prolonged silence clears the meter. Auto conservatively rejects spectrally identifiable multiple tones. Harmonic overlap can make two simultaneously sounded strings indistinguishable from one: never promise guaranteed chord rejection. A matching target within ±5 cents is considered in tune only for a fresh trustworthy measurement; a held/undecidable reading is not shown as green.

**Microphone ownership:** tuner and exercise recorder use one coordinated audio lease. A recording has priority: opening the tuner **cannot stop or overwrite** an active recording, and the tuner displays an explanation instead of capturing the microphone. Opening a recorder can stop a tuner; the draft recording is not lost. Opening the tuner also **stops the metronome**, including minimized playback, to avoid acoustic click interference. Closing the tuner with **X** or **Escape** releases the microphone and restores navigation and focus. Clicking the background outside the dialog **does not close** it. A route change, Stop and unmount release audio resources, including late permission grants. Microphone access requires a secure context (HTTPS or browser-recognized localhost).

**Verification:** `cd frontend; npm ci; npm run test:intake; npm test; npm run test:tuner; npm run build`. GitHub CI includes pitch accuracy, quiet decay, noise, octave/inharmonic profiles, double-note ambiguity, recorder priority, plus real-component integration of tuner, metronome, focus and inert lifecycle. The owner confirmed all six open strings auto-recognized, sustain audible, the guitar fully tuned, closing with X/Escape, non-dismissive backdrop and proper recording protection in a real browser. Independent comparison to a reference tuner, device/permission edge cases, mobile layout and keyboard-only accessibility have not all been performed on physical devices and must not be presented as completed QA.

No database migrations or uploaded audio are introduced.

## Review corrections (PR #99)
- Stable recognized notes remain visible even when the selected target is more than 200 cents away; the status then says wrong string and can never be green.
- The ±50-cent meter clamps its accessible numeric value and needle, while visible text and aria-valuetext retain the full cents deviation. An unavailable reading has no meter role/value.
- A newly opened modal clears old microphone errors. The parent App.vue exclusively owns app-shell inert state; the Teleported tuner uses a document-level focus guard that never writes inert, and the header restores focus to the launcher after X/Escape. Outside clicks are intentionally ignored.
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

## Independent review BUG-98-REVIEW-03 — polyphonic false-green prevention (21 Oct 2026)

The Auto mode now asks the shared YIN+FFT detector for a **conservative monophonic confidence check**. `spectralFundamental` identifies strong additional spectral peaks outside a plausible (slightly inharmonic) harmonic family; it also marks pronounced nested 3rd/4th-harmonic families as ambiguous when they suggest another open guitar string. Such evidence blocks automatic target confirmation and green `inTune` on that audio frame. Manual mode is unaffected, but switching back to Auto clears any previous/manual reading and requires fresh stabilization.

Regression tests include 150 synthesized mixtures covering every open-string pair and five relative volume levels with two phase profiles, plus clean-string/overtone/inharmonic checks in Auto. The conservative filter will sometimes withhold a single pluck with unusual harmonic content, but it must not invent a positive "in tune" result from an observably polyphonic signal.

**Physical limit:** an E2 harmonic series can have spectral frequencies identical to independent B3/E4 tones at about 3×/4× the fundamental. No algorithm examining a short monophonic waveform can unambiguously infer how many strings were plucked if the spectra overlap. A weak E4 (20% of E2 in these deterministic synthetic fixtures) may remain indistinguishable and therefore yield E2. The UI asks users to pluck just one open string; manual target selection is available. Do **not** claim perfect chord detection or that green is a guarantee of a single physical source. Real acoustic microphone acceptance is still required.


## BUG-98-REVIEW-04 — modal inert lifecycle (21 October 2026)

The parent `App.vue` is now the **single owner** of `.app-shell.inert` through its reactive `:inert="metronomeUi.open || tunerOpen"` binding. `modal-focus.ts` is strictly a focus guard (document-level `focusin` trap and removal); it never sets, snapshots or restores the shell's `inert` property. This removes the parent/child race: Vue clears `inert` on close, and a post-flush tuner cleanup can no longer reapply a stale true value.

The updated unit regression recreates this ordering. A new integrated headless Vue component regression mounts **real App.vue and real TunerDialog.vue**, not a tuner stub, verifying teleported focus isolation, `inert` ownership, close button, Escape, route change, focus return, listener cleanup and repeated opening. The metronome shell/recording tests remain part of frontend CI. Follow up with real browser keyboard and mic testing; a headless renderer is not a full browser.

## User acceptance UX change: outside click must not dismiss (21 October 2026)

During the actual guitar test, the owner confirmed **all six strings auto-recognized**, **pitch sustained into the decay**, and **the full guitar successfully tuned**. The owner also successfully tested closing via X, Escape and backdrop; however, backdrop dismissal was judged inconvenient and explicitly removed from the intended behavior.

**Accepted interaction contract:**
- Closing with the **X** button and **Escape** remains supported.
- Clicking the darkened/background area outside the dialog **does not close the tuner** and must not stop audio capture.
- The page remains inert while the dialog is open, and focus is restored when an explicit dismissal happens.
- Navigating away (if applicable) or unmounting must still release the microphone.

Implementation: remove the `@mousedown.self="close"` handler from the Teleported backdrop; no alternative click/pointer dismissal listeners. The integrated real-Vue-component regression asserts the absence of dismiss handlers while the modal remains open, and verifies Escape still closes and restores focus. Manual verification on real browser still required for the updated UX.
