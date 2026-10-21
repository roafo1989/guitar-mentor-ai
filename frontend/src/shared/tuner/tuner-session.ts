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
 * A historical three-frame median smooths a steady pitch and establishes
 * which note is tracked. During a continuous tuning adjustment the latest
 * credible pitch naturally leads this median: hiding it would remove the
 * needle and physical string highlight exactly while a peg is being turned.
 *
 * Once the tracker has stabilized, display the most recent reliable candidate
 * when the median lags it by >5 cents. A *separate* stability check is required
 * before confirming "in tune" in the UI: the latest candidate alone is never
 * proof of a steady, correctly tuned string.
 *
 * Null candidates still return the bounded held measurement. Large jumps
 * between strings invalidate the tracker and require three fresh readings.
 */
export function displayPitch(
  state: PitchTrackState, candidate: number | null, nowMs: number,
): number | null {
  const stabilized = trackPitch(state, candidate, nowMs);
  if (stabilized === null || candidate === null ||
      !Number.isFinite(candidate) || candidate <= 0) return stabilized;
  // The track is already confirmed: expose its current valid pitch even
  // while the median catches up. trackPitch has already rejected large
  // note changes (>25 cents between successive samples).
  return Math.abs(cents(candidate, stabilized)) > 5 ? candidate : stabilized;
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
