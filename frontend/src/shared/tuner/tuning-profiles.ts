import { STRINGS } from './pitch.ts';

export type TuningId = 'STANDARD' | 'DROP_D' | 'OPEN_G';
export interface TunerString { readonly number: number; readonly note: string; readonly hz: number }
export interface TuningProfile {
  readonly id: TuningId;
  readonly labelKey: string;
  readonly strings: ReadonlyArray<TunerString>;
}

const SEMITONES: Record<string, number> = {
  C: 0, 'C#': 1, D: 2, 'D#': 3, E: 4, F: 5,
  'F#': 6, G: 7, 'G#': 8, A: 9, 'A#': 10, B: 11,
};
/** Equal temperament; A4 = 440 Hz. Includes octave, never just pitch class. */
export function noteFrequency(note: string): number {
  const match = /^([A-G]#?)([0-8])$/.exec(note);
  if (!match || SEMITONES[match[1]] === undefined) throw new Error('Invalid note');
  const midi = (Number(match[2]) + 1) * 12 + SEMITONES[match[1]];
  return 440 * 2 ** ((midi - 69) / 12);
}
function strings(notes: readonly string[]): ReadonlyArray<TunerString> {
  return Object.freeze(notes.map((note, index) => Object.freeze({
    number: 6 - index, note, hz: noteFrequency(note),
  })));
}
export const TUNING_IDS: readonly TuningId[] = ['STANDARD', 'DROP_D', 'OPEN_G'];
export const TUNINGS: Readonly<Record<TuningId, TuningProfile>> = Object.freeze({
  STANDARD: Object.freeze({ id: 'STANDARD', labelKey: 'tuner.standard', strings: STRINGS }),
  DROP_D: Object.freeze({
    id: 'DROP_D', labelKey: 'tuner.dropD',
    strings: strings(['D2', 'A2', 'D3', 'G3', 'B3', 'E4']),
  }),
  OPEN_G: Object.freeze({
    id: 'OPEN_G', labelKey: 'tuner.openG',
    strings: strings(['D2', 'G2', 'D3', 'G3', 'B3', 'D4']),
  }),
});
export const TUNING_STORAGE_KEY = 'guitar-mentor.tuner.preferences.v1';
function validTuningId(value: unknown): value is TuningId {
  return typeof value === 'string' && TUNING_IDS.some(id => id === value);
}
export function readTuningPreference(): TuningId {
  try {
    if (typeof localStorage === 'undefined') return 'STANDARD';
    const raw = localStorage.getItem(TUNING_STORAGE_KEY);
    if (!raw) return 'STANDARD';
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object') return 'STANDARD';
    const data = value as { schemaVersion?: unknown; tuningId?: unknown };
    return data.schemaVersion === 1 && validTuningId(data.tuningId) ? data.tuningId : 'STANDARD';
  } catch {
    return 'STANDARD';
  }
}
export function saveTuningPreference(id: TuningId): void {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(TUNING_STORAGE_KEY, JSON.stringify({ schemaVersion: 1, tuningId: id }));
    }
  } catch {
    // Private browsing / restricted storage: keep the active choice in memory.
  }
}
