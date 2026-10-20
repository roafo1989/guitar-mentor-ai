import { STRINGS, cents } from './pitch.ts';

/** Transient pitch dropouts during the string's decay must not erase a valid note. */
export const HOLD_MS = 700;
export interface PitchTrackState {
  history: number[];
  lastSampleAt: number | null;
  frequency: number | null;
}
export function newPitchTrack(): PitchTrackState {
  return { history: [], lastSampleAt: null, frequency: null };
}
export function resetPitchTrack(state: PitchTrackState): void {
  state.history.length = 0;
  state.lastSampleAt = null;
  state.frequency = null;
}
/**
 * Require three consistent readings initially. Retain a verified reading for
 * at most HOLD_MS during short invalid frames, but immediately invalidate it
 * when a reliably different tone arrives. Do not extend the hold on null.
 */
export function trackPitch(state: PitchTrackState, candidate: number | null, nowMs: number): number | null {
  if (!Number.isFinite(nowMs)) {
    resetPitchTrack(state);
    return null;
  }
  if (state.lastSampleAt !== null &&
      (nowMs < state.lastSampleAt || nowMs - state.lastSampleAt > HOLD_MS)) {
    resetPitchTrack(state);
  }
  if (candidate === null || !Number.isFinite(candidate) || candidate <= 0) {
    return state.frequency;
  }
  if (state.history.length && Math.abs(cents(candidate, state.history[state.history.length - 1])) > 25) {
    state.history.length = 0;
    state.frequency = null;
  }
  state.history.push(candidate);
  if (state.history.length > 3) state.history.shift();
  state.lastSampleAt = nowMs;
  if (state.history.length === 3) {
    state.frequency = [...state.history].sort((a, b) => a - b)[1];
  }
  return state.frequency;
}

/**
 * Auto mode considers only known open-string targets. Large detunings beyond
 * 200 cents are deliberately left to manual selection. The 70-cent margin
 * prevents repeated switching near boundaries between strings.
 */
export function autoStringIndex(frequency: number | null, previous: number | null): number | null {
  if (frequency === null || !Number.isFinite(frequency) || frequency <= 0) return null;
  const distances = STRINGS.map(string => Math.abs(cents(frequency, string.hz)));
  let closest = 0;
  for (let i = 1; i < distances.length; i++) {
    if (distances[i] < distances[closest]) closest = i;
  }
  if (distances[closest] > 200) return null;
  if (previous !== null && previous >= 0 && previous < STRINGS.length && previous !== closest &&
      distances[previous] - distances[closest] < 70) return previous;
  return closest;
}
