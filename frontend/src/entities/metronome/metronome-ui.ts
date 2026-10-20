import { reactive } from 'vue';
import { metronome } from '../../shared/audio/metronome';

/** Shared presentation state; the audio engine is the singleton implemented in #86. */
export const metronomeUi = reactive({
  open: false,
  sourceExercise: null as string | null,
  returnFocus: null as HTMLElement | null,
});

/** Header retains the last chosen tempo, initially 80 BPM. Never starts sound. */
export function openMetronome(trigger: HTMLElement): void {
  metronomeUi.returnFocus = trigger;
  metronomeUi.open = true;
}

/** Exercise opens with its recommended tempo; it never writes back to the exercise. */
export function openExerciseMetronome(trigger: HTMLElement, source: string, bpm: number): void {
  // A new source must not inherit the previous exercise's audio playback.
  metronome.stop();
  metronomeUi.sourceExercise = source;
  if (!metronome.setBpm(bpm)) {
    // Invalid catalog data must not overwrite the user's previously valid tempo.
    metronomeUi.sourceExercise = null;
  }
  openMetronome(trigger);
}

export function closeMetronome(): void {
  metronome.stop();
  metronomeUi.open = false;
}

/** Call after successful assignment or when the currently displayed material disappears. */
export function leaveMetronomeExercise(source: string): void {
  if (metronomeUi.sourceExercise === source) {
    metronomeUi.sourceExercise = null;
    closeMetronome();
  }
}

/** Independently usable from recorder/tuner integration: never resumes playback. */
export function stopMetronomeForAudioCapture(): void {
  closeMetronome();
  metronomeUi.sourceExercise = null;
}
