# #86 — Web Audio metronome engine

Scope: isolated frontend audio core for 4/4, BPM 20–240; header/modal and exercise-card integration belong to #87. No backend, database, microphone, new network requests, secrets, audio files or autoplay.

## API contract for #87

Import the single shared instance from frontend/src/shared/audio/metronome.ts:

~~~ts
import { metronome } from '../../shared/audio/metronome'; // from src/entities/metronome/

const unsubscribe = metronome.subscribe((state) => {
  // state = { status, bpm, beat, error }
});
metronome.setBpm(84);     // boolean; rejects out-of-range/non-integer input
await metronome.start(); // boolean; call from a user gesture, never on mount
metronome.stop();        // immediate silence, clears timers and context
unsubscribe();           // on component unmount
~~~

Statuses: ready (before first run), starting, running, stopped, unavailable. Error code: web-audio-unavailable, to be localized in #87; raw browser exceptions are not displayed. The beat value is 1..4 when it has sounded, otherwise null. subscribe immediately emits a snapshot and returns an unsubscribe function. Snapshot is copied so a subscriber cannot mutate the engine.

The exported singleton is the common audio owner for the header and exercise card. Do not instantiate another engine in either view. Changing BPM live cancels pending clicks and starts a new bar on accented beat 1. The default 80 BPM is for standalone use; #87 can initialize from exercise.bpm. The engine does not synchronize playback to an MP3 or analyze a guitar performance.

When **any** UI route/window logic needs to stop the click (closing modal, leaving lesson, starting mic recording, switching to tuner #98), call metronome.stop(). The engine itself stops on visibilitychange:hidden, pagehide, and dispose(); #87 owns the remaining application-specific lifecycle hooks. Do not call dispose() in a child component that is merely unsubscribing from a shared singleton.

## Scheduling and cleanup

- One AudioContext, lazily allocated only by start(), and one 25 ms JS scheduling interval. Oscillator/Gain nodes are scheduled against AudioContext.currentTime with a 100 ms look-ahead; the accent is a higher-pitched/louder first click.
- The UI beat callback uses cancelable timeout notifications aligned to scheduled sound; visual timing may drift under system load even though audio uses the Web Audio clock. Exact audible timing still requires a real browser/device listening check.
- stop(), pagehide, hidden tab and dispose() cancel pending visual callbacks, stop/disconnect nodes, remove event listeners and close the context. Stale async resume() completions cannot restart a stopped engine.
- Construction/resume/oscillator failures result in unavailable without throwing through the app. Later explicit start() can retry. Background scheduler stalls skip missed beats; they do not dump a burst of catch-up clicks.

## Automated tests

From frontend/ after npm ci:

~~~powershell
npm test
npm run build
~~~

npm test uses Node 22's TypeScript strip-types mode and built-in test runner (no new dependency). Fake AudioContext and deterministic timers test no autoplay, BPM bounds, 1–4/accent and intervals at 20/84/240, duplicate starts, live changes, cleanup on hidden/pagehide/dispose, unavailable, async resume race and scheduler stalls. Frontend CI runs both test and build on PRs touching the frontend.

## Manual audio acceptance (owner, Windows Chrome)

Until #87 connects its UI, this is a **developer-only** way to hear the core:
1. Run npm run dev inside frontend, open the app in Chrome and DevTools Console.
2. Evaluate the following snippet, then press the newly inserted button. Do not automate pressing it.

~~~js
const { metronome } = await import('/src/shared/audio/metronome.ts');
const button = document.createElement('button');
button.textContent = 'Toggle test metronome';
button.onclick = () => {
  if (metronome.getState().status === 'running') metronome.stop();
  else void metronome.start();
};
document.body.prepend(button);
~~~

3. From Console run metronome.setBpm(20), then 84 and 240 while playing. Check first beat pitch/volume, no double clicking, changing BPM, explicit stop/restart. Switch tab to hidden, verify sound stops and does not automatically resume.
4. Remove the temporary button and call metronome.stop(). Browser autoplay rules require direct user interaction.

**Not yet accepted:** actual acoustic perception, long-run hardware clock accuracy, modal keyboard/a11y, the #87 UI and end-to-end navigation/recorder/tuner interactions. No manual musical PASS is claimed. There are no DB migrations or new media assets; rollback is simply reverting the frontend branch/PR before merge.
