/** TOOL-02: serializable, strictly validated rhythmic configuration (no AudioContext). */
export type TempoUnit = 'quarter' | 'eighth' | 'dotted-quarter';
export type Accent = 'strong' | 'medium' | 'weak' | 'mute';
export type Position = { accent: Accent; volumePercent: number };
export interface MetronomeConfig {
  bpm: number;
  numerator: number;
  denominator: 2 | 4 | 8 | 16;
  tempoUnit: TempoUnit;
  groups: number[];
  subdivision: 1 | 2 | 3 | 4;
  masterVolumePercent: number;
  accentVolumes: { strong: number; medium: number; weak: number };
  subdivisionVolumePercent: number;
  positions: Position[];
}
export type MetronomePreset = { id: string; name: string; config: MetronomeConfig };
export type MetronomeStore = { schemaVersion: 1; currentConfig: MetronomeConfig; presets: MetronomePreset[] };
export const STORE_KEY = 'guitar-mentor.metronome.v1';
export const MAX_STORE_BYTES = 65536;
export const FAST_METERS = ['2/4', '3/4', '4/4', '5/4', '6/8', '7/8', '9/8', '12/8'] as const;

const DENOMINATORS = [2, 4, 8, 16] as const;
const TEMPO_UNITS: TempoUnit[] = ['quarter', 'eighth', 'dotted-quarter'];
const ACCENTS: Accent[] = ['strong', 'medium', 'weak', 'mute'];
const percent = (value: unknown): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, names: string[]): boolean =>
  Object.keys(value).length === names.length && names.every(key => Object.hasOwn(value, key));
const configKeys = ['bpm','numerator','denominator','tempoUnit','groups','subdivision','masterVolumePercent','accentVolumes','subdivisionVolumePercent','positions'];

export function validConfig(value: unknown): value is MetronomeConfig {
  if (!object(value) || !exactKeys(value, configKeys)) return false;
  const n = value.numerator;
  if (!Number.isInteger(value.bpm) || (value.bpm as number) < 20 || (value.bpm as number) > 240 ||
      !Number.isInteger(n) || (n as number) < 1 || (n as number) > 16 ||
      !DENOMINATORS.includes(value.denominator as 2) ||
      !TEMPO_UNITS.includes(value.tempoUnit as TempoUnit) ||
      ![1, 2, 3, 4].includes(value.subdivision as number) ||
      !percent(value.masterVolumePercent) || !percent(value.subdivisionVolumePercent)) return false;
  if (!Array.isArray(value.groups) || !value.groups.length || value.groups.length > (n as number) ||
      value.groups.some(g => !Number.isInteger(g) || g < 1) ||
      value.groups.reduce((sum: number, g: number) => sum + g, 0) !== n) return false;
  if (!object(value.accentVolumes) ||
      !exactKeys(value.accentVolumes, ['strong', 'medium', 'weak']) ||
      !percent(value.accentVolumes.strong) || !percent(value.accentVolumes.medium) ||
      !percent(value.accentVolumes.weak)) return false;
  return Array.isArray(value.positions) && value.positions.length === n &&
    value.positions.every((p: unknown) => object(p) &&
      exactKeys(p, ['accent', 'volumePercent']) &&
      ACCENTS.includes(p.accent as Accent) && percent(p.volumePercent));
}

export function copyConfig(config: MetronomeConfig): MetronomeConfig {
  return { ...config, groups: [...config.groups], accentVolumes: { ...config.accentVolumes },
    positions: config.positions.map(p => ({ ...p })) };
}

export function meterConfig(numerator = 4, denominator: 2 | 4 | 8 | 16 = 4, bpm = 80): MetronomeConfig {
  if (!Number.isInteger(numerator) || numerator < 1 || numerator > 16 ||
      !DENOMINATORS.includes(denominator) || !Number.isInteger(bpm) || bpm < 20 || bpm > 240)
    throw new RangeError('Invalid meter or BPM');
  const key = String(numerator) + '/' + String(denominator);
  const defaults: Record<string, number[]> = {
    '5/4':[3,2], '6/8':[3,3], '7/8':[2,2,3], '9/8':[3,3,3], '12/8':[3,3,3,3],
  };
  const groups = [...(defaults[key] ?? [numerator])];
  const beginnings = new Set<number>();
  let at = 0;
  for (const group of groups) { beginnings.add(at); at += group; }
  return {
    bpm, numerator, denominator,
    tempoUnit: denominator === 8 && numerator % 3 === 0 && numerator >= 6 ? 'dotted-quarter' : 'quarter',
    groups, subdivision: 1, masterVolumePercent: 50,
    accentVolumes: { strong:100, medium:65, weak:35 }, subdivisionVolumePercent:25,
    positions: Array.from({ length:numerator }, (_, i) => ({
      accent: i === 0 ? 'strong' : beginnings.has(i) ? 'medium' : 'weak',
      volumePercent:100,
    })),
  };
}
export const defaultMetronomeConfig = (): MetronomeConfig => meterConfig();

export function regroup(config: MetronomeConfig, groups: number[]): MetronomeConfig | null {
  if (!Array.isArray(groups) || groups.length < 1 || groups.some(g => !Number.isInteger(g) || g < 1) ||
      groups.reduce((sum, g) => sum + g, 0) !== config.numerator) return null;
  const next = copyConfig(config); next.groups = [...groups];
  // Grouping alone never overwrites deliberately chosen accents.
  return next;
}

export function positionSeconds(c: MetronomeConfig): number {
  const quarterLength = c.tempoUnit === 'quarter' ? 1 : c.tempoUnit === 'eighth' ? 0.5 : 1.5;
  return 60 / c.bpm * (4 / c.denominator) / quarterLength;
}
export function cellGain(c: MetronomeConfig, index: number, subdivision = false): number {
  const position = c.positions[index];
  if (!position || position.accent === 'mute') return 0;
  const level = subdivision ? c.subdivisionVolumePercent : c.accentVolumes[position.accent];
  // Master gain is applied separately in the engine and must not be multiplied twice.
  return level / 100 * position.volumePercent / 100;
}
export function audible(c: MetronomeConfig): boolean {
  return c.masterVolumePercent > 0 && c.positions.some((p, i) => cellGain(c, i) > 0 ||
    (c.subdivision > 1 && cellGain(c, i, true) > 0));
}
export function parseGroups(raw: string, numerator: number): number[] | null {
  if (!/^\s*\d+(?:\s*\+\s*\d+)*\s*$/.test(raw)) return null;
  const groups = raw.split('+').map(n => Number(n.trim()));
  return groups.length <= numerator && groups.every(g => Number.isInteger(g) && g > 0) &&
    groups.reduce((sum, g) => sum + g, 0) === numerator ? groups : null;
}
const byteCount = (raw: string): number => new TextEncoder().encode(raw).byteLength;
export function validStore(value: unknown): value is MetronomeStore {
  if (!object(value) || !exactKeys(value, ['schemaVersion','currentConfig','presets']) ||
      value.schemaVersion !== 1 || !validConfig(value.currentConfig) || !Array.isArray(value.presets) ||
      value.presets.length > 20) return false;
  const ids = new Set<string>();
  for (const p of value.presets) {
    if (!object(p) || !exactKeys(p, ['id','name','config']) ||
        typeof p.id !== 'string' || p.id.length < 1 || p.id.length > 128 || ids.has(p.id) ||
        typeof p.name !== 'string' || p.name.trim() !== p.name ||
        p.name.length < 1 || p.name.length > 60 || !validConfig(p.config)) return false;
    ids.add(p.id);
  }
  return true;
}
export function readMetronomeStore(): { store: MetronomeStore | null; error: boolean } {
  try {
    const raw = globalThis.localStorage?.getItem(STORE_KEY);
    if (raw == null) return { store:null, error:false };
    if (byteCount(raw) > MAX_STORE_BYTES) return { store:null, error:true };
    const parsed: unknown = JSON.parse(raw);
    return validStore(parsed) ? { store:parsed, error:false } : { store:null, error:true };
  } catch { return { store:null, error:true }; }
}
export function saveMetronomeStore(store: MetronomeStore): boolean {
  if (!validStore(store)) return false;
  const raw = JSON.stringify(store);
  if (byteCount(raw) > MAX_STORE_BYTES) return false;
  try { globalThis.localStorage?.setItem(STORE_KEY, raw); return !!globalThis.localStorage; }
  catch { return false; }
}
/** Taps measure the explicitly displayed BPM unit, not an implicit quarter. */
export class TapTempo {
  private times: number[] = [];
  tap(now: number): { count: number; bpm: number | null; outOfRange: boolean } {
    if (!Number.isFinite(now)) return { count: this.times.length, bpm:null, outOfRange:false };
    if (this.times.length && (now <= this.times.at(-1)! || now - this.times.at(-1)! > 4000))
      this.times = [];
    this.times.push(now);
    if (this.times.length > 9) this.times.shift();
    const intervals = this.times.slice(1).map((t, i) => t - this.times[i]).sort((a,b) => a-b);
    if (intervals.length < 3) return { count: this.times.length, bpm:null, outOfRange:false };
    const mid = Math.floor(intervals.length / 2);
    const median = intervals.length % 2 ? intervals[mid] : (intervals[mid-1] + intervals[mid])/2;
    const bpm = Math.round(60000 / median);
    return bpm >= 20 && bpm <= 240
      ? { count:this.times.length, bpm, outOfRange:false }
      : { count:this.times.length, bpm:null, outOfRange:true };
  }
  reset(): void { this.times = []; }
}
