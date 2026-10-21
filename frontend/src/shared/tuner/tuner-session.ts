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
 * A historical three-frame median is useful for smooth pitch tracking, but
 * it must not be passed off as a *fresh* reading after the latest credible
 * candidate changes. A difference over the tuning tolerance suppresses the
 * visual reading for that frame while tracking continues normally.
 *
 * Null candidates still return the bounded hold value; the caller marks it
 * explicitly as held, never green. No new device/stream is acquired here.
 */
export function displayPitch(
  state: PitchTrackState, candidate: number | null, nowMs: number,
): number | null {
  const stabilized = trackPitch(state, candidate, nowMs);
  if (stabilized !== null && candidate !== null &&
      Number.isFinite(candidate) && candidate > 0 &&
      Math.abs(cents(candidate, stabilized)) > 5) return null;
  return stabilized;
}

/**
 * Auto mode considers only known open-string targets. Large detunings beyond
 * 175 cents are deliberately left to manual selection on first detection.
 * While tracking an established string, tolerate up to 235 cents to avoid
 * flicker close to the G3/B3 boundary; switch only for a clearly nearer target.
 */
export function autoStringIndex(
  frequency: number | null, previous: number | null,
  strings: ReadonlyArray<{ hz: number }> = STRINGS,
): number | null {
  if (frequency === null || !Number.isFinite(frequency) || frequency <= 0) return null;
  const distances = strings.map(string => Math.abs(cents(frequency, string.hz)));
  let closest = 0;
  for (let i = 1; i < distances.length; i++) {
    if (distances[i] < distances[closest]) closest = i;
  }
  const hasPrevious = previous !== null && previous >= 0 && previous < strings.length;
  if (hasPrevious && distances[previous] <= 235) {
    // At a boundary, prefer the last verified open string. A new one must
    // become substantially closer before it can displace the current target.
    if (closest === previous || distances[closest] > 175 ||
        distances[previous] - distances[closest] < 70) return previous;
  }
  // Do not invent a guitar string from a pitch in the ambiguous gap.
  return distances[closest] <= 175 ? closest : null;
}
