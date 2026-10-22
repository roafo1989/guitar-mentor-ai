# Issue #109 — Background metronome implementation

## Architecture
The singleton `MetronomeEngine` retains its public API and delegates audible timing to `metronome-processor.js`, a same-origin AudioWorklet module referenced using a static `new URL(..., import.meta.url)`. The audio render callback generates clicks on sample frames, independent of main-thread `setInterval` throttling. Main-thread beat messages only update the display; returning to a visible tab requests a snapshot. The processor validates incoming configuration, session ID, monotonically increasing command sequence, and BPM/volume bounds.

Active configuration is retained until a bar boundary; repeated CONFIG commands replace one pending config. BPM immediately resets to the first beat, propagates into pending, and does not accidentally consume that pending config on the reset beat. Master volume updates immediately. This has no new database, network, storage or microphone dependency.

## Lifecycle and error handling
Start must follow an explicit user gesture. When startup is delayed, Stop/pagehide invalidates the generation and closes the graph; the new Start waits for a previous context close. A module-load watchdog and per-ACK watchdog are limited to five seconds. Explicit Stop, close, route/pagehide and tuner interlock still stop sound. A hidden tab no longer stops it, while minimization and recording continue by existing UI contract. Unexpected suspend/interruption, missing AudioWorklet/module, or processor error mark the engine unavailable; automatic background retries are not attempted.

## Verification
Automated tests in `frontend/tests/metronome.test.mjs` simulate processor render frames, tempo/meter changes, command ordering, pending state, tab visibility, pagehide, cancelled startup and audio-module failure. Run `cd frontend && npm ci && npm test && npm run build`, then inspect the Vue frontend CI attached to PR #121.

Manual acceptance **not yet completed**: on Windows 10/Chrome through HTTPS/Caddy, play 84 BPM, switch to another tab for 30 seconds and six minutes, return, change to 7/8, Stop, repeat at 20/120/240 BPM, test recording, minimized window and tuner. Verify sound by ear or loopback recording, not only the animated beat indicator. Mobile/browser suspension and device sleep are not guaranteed. Do not merge without this acceptance.
