/** Hann-windowed FFT over 8192 samples: verify fundamentals independently of YIN. */
export interface SpectralCandidate { hz: number; count: number; score: number }
interface Peak { hz: number; strength: number }

export function spectralFundamental(samples: Float32Array, sampleRate: number): SpectralCandidate | null {
  const size = 8192;
  if (samples.length < size || !Number.isFinite(sampleRate) || sampleRate <= 0) return null;
  const real = new Float64Array(size), imag = new Float64Array(size);
  let mean = 0;
  for (let i = 0; i < size; i++) mean += samples[i];
  mean /= size;
  for (let i = 0; i < size; i++) real[i] =
    (samples[i] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1)));

  // Bit-reversal permutation.
  for (let i = 1, j = 0; i < size; i++) {
    let bit = size / 2;
    while (j & bit) { j ^= bit; bit /= 2; }
    j ^= bit;
    if (i < j) { const value = real[i]; real[i] = real[j]; real[j] = value; }
  }
  // In-place radix-2 FFT.
  for (let length = 2; length <= size; length *= 2) {
    const angle = -2 * Math.PI / length;
    const stepR = Math.cos(angle), stepI = Math.sin(angle);
    for (let start = 0; start < size; start += length) {
      let wr = 1, wi = 0;
      for (let k = 0; k < length / 2; k++) {
        const a = start + k, b = a + length / 2;
        const br = wr * real[b] - wi * imag[b];
        const bi = wr * imag[b] + wi * real[b];
        real[b] = real[a] - br; imag[b] = imag[a] - bi;
        real[a] += br; imag[a] += bi;
        const next = wr * stepR - wi * stepI;
        wi = wr * stepI + wi * stepR; wr = next;
      }
    }
  }
  const magnitudes = new Float64Array(size / 2 + 1);
  for (let i = 0; i < magnitudes.length; i++) magnitudes[i] = Math.hypot(real[i], imag[i]);
  const peaks: Peak[] = [];
  let maximum = 0;
  const highestBin = Math.min(Math.floor(2500 * size / sampleRate), size / 2 - 2);
  for (let i = Math.ceil(68 * size / sampleRate); i <= highestBin; i++) {
    if (magnitudes[i] < magnitudes[i - 1] || magnitudes[i] < magnitudes[i + 1]) continue;
    const left = Math.log(Math.max(magnitudes[i - 1], 1e-12));
    const center = Math.log(Math.max(magnitudes[i], 1e-12));
    const right = Math.log(Math.max(magnitudes[i + 1], 1e-12));
    const denominator = left - 2 * center + right;
    const offset = denominator ? Math.max(-0.5, Math.min(0.5, (left - right) / (2 * denominator))) : 0;
    const peak = { hz: (i + offset) * sampleRate / size, strength: magnitudes[i] };
    peaks.push(peak);
    maximum = Math.max(maximum, peak.strength);
  }
  if (!maximum) return null;
  const strongest = peaks.filter(peak => peak.strength > 0.009 * maximum)
    .sort((a, b) => b.strength - a.strength).slice(0, 40);
  function nearby(hz: number): Peak | null {
    const tolerance = Math.max(5, 0.018 * hz);
    let best: Peak | null = null;
    for (const peak of peaks) {
      if (Math.abs(peak.hz - hz) < tolerance && (!best || peak.strength > best.strength)) best = peak;
    }
    return best;
  }
  let best: SpectralCandidate | null = null;
  for (const peak of strongest) for (let divider = 1; divider <= 8; divider++) {
    const baseHz = peak.hz / divider;
    if (baseHz < 70 || baseHz > 375) continue;
    const fundamental = nearby(baseHz);
    // A loud overtone with no energy at its proposed fundamental proves nothing.
    if (!fundamental || fundamental.strength < 0.01 * maximum) continue;
    let score = 0, count = 0;
    for (let harmonic = 1; harmonic <= 8 && harmonic * baseHz <= 2500; harmonic++) {
      const match = nearby(harmonic * baseHz);
      const ratio = (match?.strength ?? 0) / maximum;
      if (ratio < 0.012 || !match) continue;
      score += 1 + 0.2 * Math.log(1 + 10 * ratio);
      count++;
    }
    if (!count) continue;
    // Higher partials only establish which fundamental is plausible.
    // Their frequencies are NOT exact multiples on real, stiff guitar strings;
    // averaging match.hz / harmonic shifts a perfectly tuned string sharp.
    // Report the measured fundamental peak, not a harmonic-derived mean.
    const choice = { hz: fundamental.hz, count, score };
    if (!best || score > best.score + 1e-6 ||
      (Math.abs(score - best.score) < 1e-6 && choice.hz < best.hz)) best = choice;
  }
  return best;
}
