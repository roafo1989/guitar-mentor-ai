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
  // A minimized metronome must not lose its beat or tempo when the student
  // reopens the very same singleton from an exercise card. Applying even an
  // identical BPM while playing would reset #86's beat scheduler.
  const status = metronome.getState().status;
  if (status === 'running' || status === 'starting') {
    metronomeUi.sourceExercise = source;
    openMetronome(trigger);
    return;
  }
  // On a stopped metronome the exercise recommendation is a convenient
  // starting point, not a mutation of the underlying exercise.
  metronomeUi.sourceExercise = source;
  if (!metronome.setBpm(bpm)) {
    // Invalid catalog data must not overwrite the user's previously valid tempo.
    metronomeUi.sourceExercise = null;
  }
  openMetronome(trigger);
}

/** Hide only the window so the student can record to a running metronome. */
export function minimizeMetronome(): void {
  if (metronome.getState().status !== 'running') return;
  metronomeUi.open = false;
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

/** Exclusive audio tools (e.g. a future tuner) may need to stop playback. Not for recording. */
export function stopMetronomeForAudioCapture(): void {
  closeMetronome();
  metronomeUi.sourceExercise = null;
}
