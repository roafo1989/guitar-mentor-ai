import { reactive } from 'vue';
import { metronome } from '../../shared/audio/metronome';
import {
  type MetronomeConfig, type MetronomePreset, copyConfig, defaultMetronomeConfig,
  readMetronomeStore, saveMetronomeStore,
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
export function openExerciseMetronome(trigger: HTMLElement, source: string, bpm: number): void {
  const status = metronome.getState().status;
  if (status === 'running' || status === 'starting') {
    metronomeUi.sourceExercise = source;
    openMetronome(trigger);
    return;
  }
  metronomeUi.sourceExercise = source;
  if (!metronome.setBpm(bpm)) metronomeUi.sourceExercise = null;
  else persistMetronomeConfig();
  openMetronome(trigger);
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
