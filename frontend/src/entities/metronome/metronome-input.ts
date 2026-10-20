/** Input validation is intentionally stricter than Number(): no NaN, decimals or scientific notation. */
export const MIN_BPM = 20;
export const MAX_BPM = 240;

export function parseMetronomeBpm(raw: string): number | null {
  const normalized = raw.trim();
  if (!/^\d{1,3}$/.test(normalized)) return null;
  const value = Number(normalized);
  return Number.isInteger(value) && value >= MIN_BPM && value <= MAX_BPM ? value : null;
}

export function stepMetronomeBpm(value: number, delta: -1 | 1): number {
  return Math.max(MIN_BPM, Math.min(MAX_BPM, value + delta));
}
