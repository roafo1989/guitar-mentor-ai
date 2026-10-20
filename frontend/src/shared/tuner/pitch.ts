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
  let sum = 0, candidate = 0;
  for (let lag = 1; lag <= maxLag + 1; lag++) {
    let difference = 0;
    for (let i = 0; i < width; i++) {
      const delta = samples[i] - samples[i + lag];
      difference += delta * delta;
    }
    differences[lag] = difference;
    sum += difference;
    if (lag >= minLag && lag <= maxLag && sum > 0 && difference * lag / sum < 0.16) {
      candidate = lag;
      break;
    }
  }
  if (!candidate) return null;
  while (candidate < maxLag && differences[candidate + 1] < differences[candidate]) candidate++;
  const left = differences[candidate - 1], center = differences[candidate], right = differences[candidate + 1];
  const denominator = left - 2 * center + right;
  const shift = denominator ? Math.max(-1, Math.min(1, (left - right) / (2 * denominator))) : 0;
  const frequency = sampleRate / (candidate + shift);
  return frequency >= 70 && frequency <= 375 && Number.isFinite(frequency) ? frequency : null;
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
