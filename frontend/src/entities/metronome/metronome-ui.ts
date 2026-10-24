import { reactive } from 'vue';
import { metronome } from '../../shared/audio/metronome';
import {
  type MetronomeConfig, type MetronomePreset, copyConfig, defaultMetronomeConfig,
  readMetronomeStore, saveMetronomeStore, validConfig, meterConfig,
} from '../../shared/audio/metronome-config.ts';

const restored = readMetronomeStore();
if (restored.store) metronome.applyConfig(restored.store.currentConfig);

/** Shared modal and persisted preferences for the single metronome instance. */
export const metronomeUi = reactive({
  open: false,
  sourceExercise: null as string | null,
  returnFocus: null as HTMLElement | null,
  presets: restored.store?.presets.map(p => ({ ...p, config:copyConfig(p.config) })) ?? [] as MetronomePreset[],
  storageError: restored.error,
  storageBlocked: restored.error,
});

export function persistMetronomeConfig(config?: MetronomeConfig): boolean {
  if (metronomeUi.storageBlocked) { metronomeUi.storageError = true; return false; }
  const accepted = config ?? metronome.getPendingConfig() ?? metronome.getConfig();
  const saved = saveMetronomeStore({
    schemaVersion: 1, currentConfig: copyConfig(accepted),
    presets: metronomeUi.presets.map(p => ({ id:p.id, name:p.name, config:copyConfig(p.config) })),
  });
  metronomeUi.storageError = !saved;
  return saved;
}
/** Explicit user action permits replacing corrupted stored data; no implicit deletion. */
export function resetMetronomePreferences(): boolean {
  metronomeUi.storageBlocked = false;
  metronomeUi.presets = [];
  metronome.applyConfig(defaultMetronomeConfig());
  return persistMetronomeConfig();
}
export function openMetronome(trigger: HTMLElement): void {
  metronomeUi.returnFocus = trigger;
  metronomeUi.open = true;
}
/** Apply the exercise's real meter, rather than retaining an unrelated header preset. */
export function openExerciseMetronome(
  trigger: HTMLElement, source: string, bpm: number, meter: string,
  tempoUnit?: 'quarter' | 'eighth' | 'dotted-quarter',
): boolean {
  const parsed = /^([0-9]{1,2})\/(2|4|8|16)$/.exec(meter.trim());
  if (!parsed || !Number.isInteger(bpm) || bpm < 20 || bpm > 240) return false;
  const numerator = Number(parsed[1]);
  const denominator = Number(parsed[2]) as 2 | 4 | 8 | 16;
  if (numerator < 1 || numerator > 16) return false;
  const status = metronome.getState().status;
  // Reopening a minimized window for the *same* piece must not reset playback.
  if ((status === 'running' || status === 'starting') &&
      metronomeUi.sourceExercise === source) {
    openMetronome(trigger);
    return true;
  }
  // A different piece (or a header-only pattern) must not inherit a running
  // clock with the wrong accents/measure length.
  if (status === 'running' || status === 'starting') metronome.stop();
  const previous = metronome.getConfig();
  if (tempoUnit && !['quarter', 'eighth', 'dotted-quarter'].includes(tempoUnit)) return false;
  const requested = meterConfig(numerator, denominator, bpm);
  if (tempoUnit) requested.tempoUnit = tempoUnit;
  requested.masterVolumePercent = previous.masterVolumePercent;
  requested.accentVolumes = { ...previous.accentVolumes };
  requested.subdivisionVolumePercent = previous.subdivisionVolumePercent;
  requested.subdivision = previous.subdivision;
  if (!validConfig(requested) || !metronome.applyConfig(requested)) return false;
  metronomeUi.sourceExercise = source;
  persistMetronomeConfig();
  openMetronome(trigger);
  return true;
}

export function minimizeMetronome(): void {
  if (metronome.getState().status !== 'running') return;
  metronomeUi.open = false;
}
export function closeMetronome(): void {
  metronome.stop();
  metronomeUi.open = false;
}
export function leaveMetronomeExercise(source: string): void {
  if (metronomeUi.sourceExercise === source) {
    metronomeUi.sourceExercise = null;
    closeMetronome();
  }
}
export function stopMetronomeForAudioCapture(): void {
  closeMetronome();
  metronomeUi.sourceExercise = null;
}
