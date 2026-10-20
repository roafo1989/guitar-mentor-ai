import { spectralFundamental } from './harmonic-spectrum.ts';

export const STRINGS = [
  { number: 6, note: 'E2', hz: 82.406889 },
  { number: 5, note: 'A2', hz: 110 },
  { number: 4, note: 'D3', hz: 146.832384 },
  { number: 3, note: 'G3', hz: 195.997718 },
  { number: 2, note: 'B3', hz: 246.941651 },
  { number: 1, note: 'E4', hz: 329.627557 },
] as const;
const NOTES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
export function cents(actual: number, target: number): number {
  return 1200 * Math.log2(actual / target);
}
export function noteName(hz: number): string {
  if (!Number.isFinite(hz) || hz <= 0) return '—';
  const midi = Math.round(69 + 12 * Math.log2(hz / 440));
  return NOTES[((midi % 12) + 12) % 12] + String(Math.floor(midi / 12) - 1);
}
// YIN-like normalized difference. Inaccurate/weak/ambiguous frames return null.
export function detectPitch(samples: Float32Array, sampleRate: number): number | null {
  if (!Number.isFinite(sampleRate) || sampleRate <= 0) return null;
  const minLag = Math.max(2, Math.floor(sampleRate / 375));
  const maxLag = Math.ceil(sampleRate / 70);
  const width = Math.min(2048, samples.length - maxLag - 2);
  if (width < 1024) return null;
  let energy = 0, mean = 0;
  for (let i = 0; i < width; i++) mean += samples[i];
  mean /= width;
  for (let i = 0; i < width; i++) energy += (samples[i] - mean) ** 2;
  if (Math.sqrt(energy / width) < 0.01) return null;
  const differences = new Float64Array(maxLag + 2);
  // Compute the full difference curve before searching for a minimum:
  // otherwise uninitialized future lags are zero and create false early peaks.
  for (let lag = 1; lag <= maxLag + 1; lag++) {
    let difference = 0;
    for (let i = 0; i < width; i++) {
      const delta = samples[i] - samples[i + lag];
      difference += delta * delta;
    }
    differences[lag] = difference;
  }
  // Evaluate every normalized-difference minimum: the first plausible lag
  // may correspond to a strong second harmonic rather than the fundamental.
  const normalized = new Float64Array(maxLag + 1);
  let sum = 0;
  for (let lag = 1; lag <= maxLag; lag++) {
    sum += differences[lag];
    normalized[lag] = sum > 0 ? differences[lag] * lag / sum : 1;
  }
  let bestScore = Infinity;
  for (let lag = minLag; lag <= maxLag; lag++) {
    if (differences[lag] <= differences[lag - 1] &&
        differences[lag] <= differences[lag + 1] && normalized[lag] < 0.16) {
      bestScore = Math.min(bestScore, normalized[lag]);
    }
  }
  if (!Number.isFinite(bestScore)) return null;
  // Prefer the earliest good period over later multiples (2*T, 3*T).
  // A shallow half-period trough from the 2nd harmonic is filtered out.
  const acceptable = Math.min(0.16, Math.max(bestScore * 1.35, bestScore + 0.002));
  let candidate = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    if (differences[lag] <= differences[lag - 1] &&
        differences[lag] <= differences[lag + 1] && normalized[lag] <= acceptable) {
      candidate = lag;
      break;
    }
  }
  if (!candidate) return null;
  const left = differences[candidate - 1], center = differences[candidate], right = differences[candidate + 1];
  const denominator = left - 2 * center + right;
  const shift = denominator ? Math.max(-1, Math.min(1, (left - right) / (2 * denominator))) : 0;
  const frequency = sampleRate / (candidate + shift);
  if (frequency < 70 || frequency > 375 || !Number.isFinite(frequency)) return null;
  // The first credible YIN period can be a harmonic or subharmonic.
  // Spectral evidence must support a proposed lower fundamental.
  const spectrum = spectralFundamental(samples, sampleRate);
  if (!spectrum) return null;
  if (spectrum.count === 1) {
    // A single sine is indistinguishable from a missing-fundamental overtone.
    // Do not invent lower pitches without independent spectral evidence.
    return Math.abs(cents(spectrum.hz, frequency)) <= 15 ? frequency : null;
  }
  const period = Math.round(sampleRate / spectrum.hz);
  if (period < minLag || period > maxLag ||
      Math.min(normalized[Math.max(minLag, period - 1)], normalized[period],
        normalized[Math.min(maxLag, period + 1)]) >= 0.16) return null;
  return spectrum.hz;
}
// Three consecutive stable frames required; transients never show "in tune".
export function stablePitch(history: number[], frequency: number | null): number | null {
  if (frequency === null || !Number.isFinite(frequency) || frequency <= 0) {
    history.length = 0;
    return null;
  }
  if (history.length && Math.abs(cents(frequency, history[history.length - 1])) > 25) history.length = 0;
  history.push(frequency);
  if (history.length > 3) history.shift();
  if (history.length < 3) return null;
  return [...history].sort((a, b) => a - b)[1];
}
