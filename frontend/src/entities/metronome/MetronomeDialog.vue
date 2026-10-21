<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { metronome, type MetronomeState } from '../../shared/audio/metronome';
import {
  type Accent, type MetronomeConfig, type TempoUnit, TapTempo, FAST_METERS,
  audible, copyConfig, meterConfig, parseGroups, validConfig,
} from '../../shared/audio/metronome-config.ts';
import { metronomeUi, persistMetronomeConfig, resetMetronomePreferences } from './metronome-ui';
import { MAX_BPM, MIN_BPM, parseMetronomeBpm, stepMetronomeBpm } from './metronome-input';


const panel = ref<'settings' | 'presets' | null>(null);
const quickMeters = ['2/4', '3/4', '4/4', '6/8', '7/8'];
const otherMeters = FAST_METERS.filter(key => !quickMeters.includes(key));
const unitMarks: Record<TempoUnit, string> = {
  quarter: '♩', eighth: '♪', 'dotted-quarter': '♩.',
};
const accentMarks: Record<Accent, string> = {
  strong: '●', medium: '◐', weak: '○', mute: '×',
};
function openPanel(kind: 'settings' | 'presets'): void {
  panel.value = kind;
}
function closePanel(): void { panel.value = null; }

const emit = defineEmits<{ close: []; minimize: [] }>();
const { t } = useI18n();
const state = ref<MetronomeState>(metronome.getState());
const active = ref<MetronomeConfig>(metronome.getConfig());
const draftConfig = ref<MetronomeConfig>(copyConfig(metronome.getPendingConfig() ?? active.value));
const draft = ref(String(draftConfig.value.bpm));
const dirty = ref(false);
const error = ref('');
const configError = ref('');
const tapStatus = ref('');
const beatSelection = ref(0);
const meterN = ref(draftConfig.value.numerator);
const meterD = ref(draftConfig.value.denominator);
const groupInput = ref(draftConfig.value.groups.join('+'));
const presetName = ref('');
const selectedPreset = ref('');
const confirmAction = ref<'replace' | 'delete' | null>(null);
const confirmReset = ref(false);
const tapper = new TapTempo();
const dialog = ref<HTMLElement | null>(null);
const bpmField = ref<HTMLInputElement | null>(null);
const tempoUnits: TempoUnit[] = ['quarter', 'eighth', 'dotted-quarter'];
const accentOrder: Accent[] = ['strong', 'medium', 'weak', 'mute'];
const denominators = [2, 4, 8, 16];
const widths = [1, 2, 3, 4];
let unsubscribe: (() => void) | null = null;
let previousOverflow = '';
let lastRequestedJson = JSON.stringify(draftConfig.value);
const silent = computed(() => !audible(draftConfig.value));
// Use the same BPM for +/- and their disabled states. While a preset is
// pending, the playing BPM can differ from the number in the editor.
const stepReferenceBpm = computed(() => parseMetronomeBpm(draft.value) ?? state.value.bpm);
const differentPlayingPattern = computed(() => state.value.status === 'running' &&
  JSON.stringify({
    n:active.value.numerator, d:active.value.denominator, groups:active.value.groups,
    tempoUnit:active.value.tempoUnit, subdivision:active.value.subdivision,
    positions:active.value.positions,
  }) !== JSON.stringify({
    n:draftConfig.value.numerator, d:draftConfig.value.denominator, groups:draftConfig.value.groups,
    tempoUnit:draftConfig.value.tempoUnit, subdivision:draftConfig.value.subdivision,
    positions:draftConfig.value.positions,
  }));
const chosenPreset = computed(() => metronomeUi.presets.find(p => p.id === selectedPreset.value));

function sync(next: MetronomeState): void {
  state.value = { ...next };
  active.value = metronome.getConfig();
  const requested = metronome.getPendingConfig() ?? active.value;
  const signature = JSON.stringify(requested);
  if (!dirty.value && signature !== lastRequestedJson) {
    draftConfig.value = copyConfig(requested);
    meterN.value = requested.numerator;
    meterD.value = requested.denominator;
    groupInput.value = requested.groups.join('+');
    lastRequestedJson = signature;
  }
  // The editable BPM follows the last requested configuration, not the still
  // sounding BPM while a preset/configuration waits for the next bar.
  // A partially typed or invalid BPM is an unsaved edit even when the rhythm
  // config is clean. Scheduler ticks must not silently erase it or leave a
  // valid-looking number alongside an obsolete validation error.
  if (!dirty.value && parseMetronomeBpm(draft.value) !== null &&
      document.activeElement !== bpmField.value)
    draft.value = String(requested.bpm);
  if (next.error) error.value = '';
}
function store(): void {
  if (!persistMetronomeConfig()) configError.value = t('metronome.storeFailure');
}
function commitTempo(): boolean {
  const parsed = parseMetronomeBpm(draft.value);
  if (parsed === null) { error.value = t('metronome.invalidBpm'); return false; }
  error.value = '';
  metronome.setBpm(parsed);
  draftConfig.value.bpm = parsed;
  store();
  return true;
}
function onTempoInput(event: Event): void {
  draft.value = (event.target as HTMLInputElement).value;
  const parsed = parseMetronomeBpm(draft.value);
  if (parsed === null) { error.value = t('metronome.invalidBpm'); return; }
  error.value = '';
  metronome.setBpm(parsed);
  draftConfig.value.bpm = parsed;
  store();
}
function stepTempo(step: -1 | 1): void {
  const bpm = stepMetronomeBpm(stepReferenceBpm.value, step);
  draft.value = String(bpm);
  error.value = '';
  metronome.setBpm(bpm);
  draftConfig.value.bpm = bpm;
  store();
}
function tap(): void {
  const result = tapper.tap(performance.now());
  if (result.bpm === null) {
    tapStatus.value = result.outOfRange ? t('metronome.invalidTap') :
      t('metronome.tapCount', { count: result.count });
    return;
  }
  tapStatus.value = t('metronome.tapApplied', { bpm: result.bpm });
  metronome.setBpm(result.bpm);
  draft.value = String(result.bpm);
  draftConfig.value.bpm = result.bpm;
  error.value = '';
  store();
}
function setMeter(n: number, d: number): void {
  if (!Number.isInteger(n) || n < 1 || n > 16 || !denominators.includes(d)) {
    configError.value = t('metronome.invalidConfig'); return;
  }
  const previous = draftConfig.value;
  // Meter selection replaces the rhythmic pattern but must not revert a
  // requested preset tempo to the BPM still sounding in the current bar.
  const next = meterConfig(n, d as 2 | 4 | 8 | 16, previous.bpm);
  // Sound mix and subdivision are not meter-specific. Preserve the user's
  // draft choices; new beat positions receive the meter's suggested accents.
  next.masterVolumePercent = previous.masterVolumePercent;
  next.accentVolumes = { ...previous.accentVolumes };
  next.subdivisionVolumePercent = previous.subdivisionVolumePercent;
  next.subdivision = previous.subdivision;
  draftConfig.value = next;
  meterN.value = n;
  meterD.value = d as 2 | 4 | 8 | 16;
  groupInput.value = next.groups.join('+');
  beatSelection.value = 0;
  dirty.value = true;
  configError.value = '';
}
function pickQuick(key: string): void {
  const [n, d] = key.split('/').map(Number);
  setMeter(n, d);
}
function changeMeter(): void { setMeter(Number(meterN.value), Number(meterD.value)); }
function changeGroups(): void {
  const groups = parseGroups(groupInput.value, draftConfig.value.numerator);
  if (!groups) { configError.value = t('metronome.invalidGroups'); return; }
  draftConfig.value.groups = groups;
  // Group change is an explicit request for a new suggested accent pattern.
  // The student can still customize every beat again before applying.
  const starts = new Set<number>();
  let offset = 0;
  for (const size of groups) { starts.add(offset); offset += size; }
  draftConfig.value.positions.forEach((position, index) => {
    position.accent = index === 0 ? 'strong' : starts.has(index) ? 'medium' : 'weak';
  });
  dirty.value = true;
  configError.value = '';
}
function changeTempoUnit(value: string): void {
  if (!tempoUnits.includes(value as TempoUnit)) return;
  draftConfig.value.tempoUnit = value as TempoUnit;
  dirty.value = true;
}
function changeSubdivision(value: string): void {
  const sub = Number(value);
  if (!widths.includes(sub)) return;
  draftConfig.value.subdivision = sub as 1 | 2 | 3 | 4;
  dirty.value = true;
}
function cycleAccent(index: number): void {
  const position = draftConfig.value.positions[index];
  if (!position) return;
  position.accent = accentOrder[(accentOrder.indexOf(position.accent) + 1) % accentOrder.length];
  beatSelection.value = index;
  dirty.value = true;
}
function changePositionVolume(value: string): void {
  const volume = Number(value);
  if (!Number.isInteger(volume) || volume < 0 || volume > 100) return;
  draftConfig.value.positions[beatSelection.value].volumePercent = volume;
  dirty.value = true;
}
function changeMaster(value: string): void {
  const volume = Number(value);
  if (!metronome.setMasterVolume(volume)) return;
  draftConfig.value.masterVolumePercent = volume;
  store();
}
function changeAccentVolume(accent: 'strong' | 'medium' | 'weak', value: string): void {
  const v = Number(value);
  if (!Number.isInteger(v) || v < 0 || v > 100) return;
  draftConfig.value.accentVolumes[accent] = v;
  dirty.value = true;
}
function changeSubdivisionVolume(value: string): void {
  const v = Number(value);
  if (!Number.isInteger(v) || v < 0 || v > 100) return;
  draftConfig.value.subdivisionVolumePercent = v;
  dirty.value = true;
}
/** Validate the actual input fields for every action that accepts a config.
 * Group text may not have dispatched its blur/change event before Save/Replace.
 * Reconcile a valid uncommitted grouping, including its suggested accents.
 */
function validateDraftFields(): boolean {
  const bpm = parseMetronomeBpm(draft.value);
  if (bpm === null) {
    error.value = t('metronome.invalidBpm');
    return false;
  }
  error.value = '';
  const groups = parseGroups(groupInput.value, draftConfig.value.numerator);
  if (!groups) {
    configError.value = t('metronome.invalidGroups');
    return false;
  }
  if (groups.length !== draftConfig.value.groups.length ||
      groups.some((size, index) => size !== draftConfig.value.groups[index]))
    changeGroups(); // reflects the newly entered grouping in draft and accents
  draftConfig.value.bpm = bpm;
  if (!validConfig(draftConfig.value)) {
    configError.value = t('metronome.invalidConfig');
    return false;
  }
  configError.value = '';
  return true;
}
function apply(): boolean {
  if (!validateDraftFields()) return false;
  // Composite loading may also carry a new BPM. It remains pending at the bar line.
  if (!metronome.applyConfig(draftConfig.value)) return false;
  dirty.value = false;
  configError.value = '';
  lastRequestedJson = JSON.stringify(draftConfig.value);
  store();
  return true;
}
function discard(): void {
  draftConfig.value = copyConfig(metronome.getPendingConfig() ?? metronome.getConfig());
  meterN.value = draftConfig.value.numerator;
  meterD.value = draftConfig.value.denominator;
  groupInput.value = draftConfig.value.groups.join('+');
  // Discard also abandons an invalid/partial BPM edit, not only rhythm fields.
  draft.value = String(draftConfig.value.bpm);
  error.value = '';
  beatSelection.value = Math.min(beatSelection.value, draftConfig.value.numerator - 1);
  lastRequestedJson = JSON.stringify(draftConfig.value);
  dirty.value = false; configError.value = '';
}
function savePreset(): void {
  const name = presetName.value.trim();
  if (!name || name.length > 60 || metronomeUi.presets.length >= 20) {
    configError.value = t('metronome.invalidPreset'); return;
  }
  if (!validateDraftFields()) return;
  const id = globalThis.crypto?.randomUUID?.() ?? 'local-' + String(Date.now()) + '-' + String(Math.random());
  metronomeUi.presets.push({ id, name, config:copyConfig(draftConfig.value) });
  selectedPreset.value = id;
  store();
}
function loadPreset(): void {
  const p = chosenPreset.value;
  if (!p) return;
  draftConfig.value = copyConfig(p.config);
  meterN.value = p.config.numerator;
  meterD.value = p.config.denominator;
  groupInput.value = p.config.groups.join('+');
  draft.value = String(p.config.bpm);
  error.value = ''; // Preset selection explicitly replaces an invalid BPM edit.
  beatSelection.value = 0;
  dirty.value = true;
  apply();
}
function renamePreset(): void {
  const name = presetName.value.trim();
  if (!chosenPreset.value || !name || name.length > 60) {
    configError.value = t('metronome.invalidPreset'); return;
  }
  chosenPreset.value.name = name;
  store();
}
function confirmPreset(): void {
  const p = chosenPreset.value;
  if (!p || !confirmAction.value) return;
  if (confirmAction.value === 'delete') {
    metronomeUi.presets = metronomeUi.presets.filter(item => item.id !== p.id);
    selectedPreset.value = '';
  } else {
    if (!validateDraftFields()) return;
    p.config = copyConfig(draftConfig.value);
  }
  confirmAction.value = null;
  store();
}
function resetPreferences(): void {
  if (resetMetronomePreferences()) {
    selectedPreset.value = '';
    presetName.value = '';
    beatSelection.value = 0;
    discard();
    confirmReset.value = false;
    configError.value = '';
  } else configError.value = t('metronome.storeFailure');
}
function start(): void {
  if (!commitTempo()) return;
  if (dirty.value && !apply()) return;
  void metronome.start(); // direct click gesture for autoplay policy
}
function togglePlayback(): void {
  if (state.value.status === 'starting') return;
  if (state.value.status === 'running') metronome.stop();
  else start();
}
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopPropagation();
    if (panel.value) closePanel(); else emit('close');
    return;
  }
  if (event.key !== 'Tab' || !dialog.value) return;
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0 &&
    (typeof element.closest !== 'function' || !element.closest('[inert]')));
  if (!focusable.length) { event.preventDefault(); dialog.value.focus(); return; }
  const first = focusable[0], last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus();
  }
}
onMounted(async () => {
  unsubscribe = metronome.subscribe(sync);
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
  await nextTick();
  bpmField.value?.focus();
});
onBeforeUnmount(() => {
  unsubscribe?.();
  document.body.style.overflow = previousOverflow;
});
</script>

<template>
  <Teleport to="body">
    <div class="metronome-backdrop">
      <section ref="dialog" class="metronome-dialog m111" role="dialog" aria-modal="true"
        aria-labelledby="metronome-title" aria-describedby="metronome-description"
        tabindex="-1" @keydown="onKeydown">
        <header class="metronome-dialog-heading m111-heading">
          <div class="m111-brand"><span class="m111-brand-mark" aria-hidden="true">♩</span>
            <h2 id="metronome-title">{{ t('metronome.title') }}</h2>
          </div>
          <span class="m111-status-pill">{{ t('metronome.status.' + state.status) }}</span>
          <button class="metronome-close" type="button" :aria-label="t('metronome.close')"
            :title="t('metronome.close')" @click="emit('close')">×</button>
        </header>
        <p id="metronome-description" class="m111-sr-only">{{ t('metronome.description') }}</p>

        <div class="m111-body" :inert="panel !== null">
          <div class="m111-cards">
            <section class="metronome-tempo m111-tempo-card">
              <div class="m111-section-head">
                <label for="metronome-bpm">{{ t('metronome.tempo') }}</label>
                <span class="m111-tempo-unit">{{ unitMarks[draftConfig.tempoUnit] }} · {{ t('metronome.units.' + draftConfig.tempoUnit) }}</span>
              </div>
              <div class="metronome-tempo-controls m111-tempo-controls">
                <button class="m111-tempo-step" type="button" :aria-label="t('metronome.slower')"
                  :disabled="stepReferenceBpm <= MIN_BPM" @click="stepTempo(-1)">−</button>
                <div class="m111-tempo-center">
                  <input id="metronome-bpm" ref="bpmField" :value="draft" @input="onTempoInput"
                    type="number" inputmode="numeric" :min="MIN_BPM" :max="MAX_BPM" step="1"
                    :aria-invalid="!!error && state.error === null"
                    :aria-describedby="error ? 'metronome-error' : 'metronome-range'"
                    @change="commitTempo" @keydown.enter.prevent="commitTempo" />
                  <span class="m111-bpm-label">BPM</span>
                  <span class="m111-edit-tip">{{ t('metronome.editTempo') }}</span>
                </div>
                <button class="m111-tempo-step" type="button" :aria-label="t('metronome.faster')"
                  :disabled="stepReferenceBpm >= MAX_BPM" @click="stepTempo(1)">+</button>
              </div>
              <div class="m111-tempo-bottom">
                <small id="metronome-range">{{ MIN_BPM }}–{{ MAX_BPM }} BPM</small>
                <button type="button" class="metronome-tap m111-tap" @click="tap">{{ t('metronome.tap') }} · TAP</button>
              </div>
              <small v-if="state.status === 'running' && (dirty || metronome.getPendingConfig())"
                class="metronome-tempo-comparison" role="status">
                {{ t('metronome.soundingTempo', { bpm: state.bpm, unit: t('metronome.units.' + active.tempoUnit) }) }}
                · {{ t(dirty ? 'metronome.editingTempo' : 'metronome.queuedTempo',
                  { bpm: draftConfig.bpm, unit: t('metronome.units.' + draftConfig.tempoUnit) }) }}
              </small>
              <small v-if="tapStatus" class="m111-feedback" role="status">{{ tapStatus }}</small>
              <p v-if="error" id="metronome-error" role="alert" class="metronome-error">{{ error }}</p>
            </section>

            <section class="m111-rhythm-card">
              <div class="m111-rhythm-top">
                <div class="m111-meter-display"><span>{{ t('metronome.meter') }}</span>
                  <strong>{{ draftConfig.numerator }}/{{ draftConfig.denominator }}</strong>
                  <small>{{ draftConfig.groups.join('+') }}</small>
                </div>
                <button type="button" class="m111-change" @click="openPanel('settings')">
                  {{ t('metronome.changeMeter') }}
                </button>
              </div>
              <div class="metronome-quick m111-quick" :aria-label="t('metronome.quickMeters')">
                <button v-for="key in quickMeters" :key="key" type="button"
                  :aria-pressed="draftConfig.numerator + '/' + draftConfig.denominator === key"
                  @click="pickQuick(key)">{{ key }}</button>
              </div>
              <div v-if="differentPlayingPattern" class="metronome-playing-pattern">
                <p class="m111-pattern-caption">{{ t('metronome.playingPattern') }}: {{ active.numerator }}/{{ active.denominator }}</p>
                <div class="metronome-count metronome-count-active" :aria-label="t('metronome.playingPattern')">
                  <div v-for="(position, index) in active.positions" :key="index"
                    class="metronome-beat"
                    :class="['accent-' + position.accent, { 'is-active': state.beat === index + 1 }]"
                    :aria-label="t('metronome.beatAccent', { number:index + 1, accent:t('metronome.accents.' + position.accent) })">
                    <span>{{ index + 1 }}</span><small>{{ accentMarks[position.accent] }}</small>
                  </div>
                </div>
                <p class="m111-pattern-caption">{{ t('metronome.editingPattern') }}</p>
              </div>
              <div class="metronome-count" :aria-label="draftConfig.numerator + '/' + draftConfig.denominator">
                <button v-for="(position, index) in draftConfig.positions" :key="index" type="button"
                  class="metronome-beat"
                  :class="['accent-' + position.accent, { 'is-active': !differentPlayingPattern && state.status === 'running' && state.beat === index + 1, 'is-selected': beatSelection === index }]"
                  :aria-label="t('metronome.beatAccent', { number: index + 1, accent: t('metronome.accents.' + position.accent) })"
                  :title="t('metronome.beatAccent', { number: index + 1, accent: t('metronome.accents.' + position.accent) })"
                  @click="cycleAccent(index)">
                  <span>{{ index + 1 }}</span><small aria-hidden="true">{{ accentMarks[position.accent] }}</small>
                </button>
              </div>
              <small class="m111-beat-help">{{ t('metronome.beatHelp') }}</small>
              <div class="m111-subdivision">
                <span>{{ t('metronome.subdivision') }}</span>
                <div class="m111-subdivision-controls">
                  <button v-for="n in widths" :key="n" type="button"
                    :aria-pressed="draftConfig.subdivision === n"
                    :aria-label="t('metronome.subdivisionCount', { count: n })"
                    @click="changeSubdivision(String(n))">{{ n }}</button>
                </div>
              </div>
              <p v-if="silent" class="m111-silent" role="status">{{ t('metronome.silent') }}</p>
              <p v-if="metronomeUi.sourceExercise" class="m111-feedback">{{ t('metronome.userMeterWarning') }}</p>
            </section>
          </div>

          <div class="m111-tools">
            <button type="button" @click="openPanel('presets')">{{ t('metronome.presets') }} <span aria-hidden="true">⌄</span></button>
            <button type="button" @click="openPanel('settings')">{{ t('metronome.details') }} <span aria-hidden="true">⚙</span></button>
          </div>
          <div class="metronome-apply m111-apply" :class="{ 'm111-apply-quiet': !dirty && !metronome.getPendingConfig() && !error }">
            <button type="button" :disabled="!dirty" @click="apply">{{ t('metronome.apply') }}</button>
            <button type="button" :disabled="!dirty && parseMetronomeBpm(draft) !== null" @click="discard">{{ t('metronome.discard') }}</button>
            <small v-if="metronome.getPendingConfig()" role="status">{{ t('metronome.pending') }}</small>
          </div>
        </div>

        <footer class="m111-footer" :inert="panel !== null">
          <p v-if="configError || metronomeUi.storageError" role="alert" class="metronome-error">
            {{ configError || t('metronome.storeFailure') }}
          </p>
          <p class="metronome-state m111-state" role="status" aria-live="polite">
            {{ t('metronome.status.' + state.status) }}
            <span v-if="metronome.getPendingConfig()"> · {{ t('metronome.pending') }}</span>
          </p>
          <p v-if="state.error" role="alert" class="metronome-error">{{ t('metronome.unavailable') }}</p>
          <div class="metronome-actions m111-actions">
            <button type="button" class="action-button m111-primary"
              :aria-disabled="state.status === 'starting'" @click="togglePlayback">
              {{ state.status === 'running' ? t('metronome.stop') : t('metronome.start') }}
            </button>
            <button v-if="state.status === 'running'" type="button"
              class="recorder-button secondary-button m111-minimize" @click="emit('minimize')">
              {{ t('metronome.minimize') }}
            </button>
          </div>
          <p class="m111-warning">{{ t('metronome.warning') }}</p>
        </footer>

        <div class="m111-sheet" :class="{ 'is-open': panel === 'settings' }"
          :aria-hidden="panel !== 'settings'" aria-labelledby="m111-settings-title">
          <div class="m111-sheet-head"><h3 id="m111-settings-title">{{ t('metronome.details') }}</h3>
            <button type="button" :aria-label="t('metronome.closeSettings')" @click="closePanel">×</button></div>
          <div class="m111-sheet-scroll">
            <div class="metronome-settings">
              <div class="metronome-meter-picker">
                <label for="metronome-numerator">{{ t('metronome.meter') }}</label>
                <select id="metronome-numerator" :value="meterN"
                  @change="meterN = Number(($event.target as HTMLSelectElement).value); changeMeter()">
                  <option v-for="n in 16" :key="n" :value="n">{{ n }}</option>
                </select>
                <span aria-hidden="true">/</span>
                <select :value="meterD" :aria-label="t('metronome.denominator')"
                  @change="meterD = Number(($event.target as HTMLSelectElement).value) as 2 | 4 | 8 | 16; changeMeter()">
                  <option v-for="d in denominators" :key="d" :value="d">{{ d }}</option>
                </select>
              </div>
              <div class="metronome-quick m111-extra-meters">
                <button v-for="key in otherMeters" :key="key" type="button"
                  :aria-pressed="draftConfig.numerator + '/' + draftConfig.denominator === key"
                  @click="pickQuick(key)">{{ key }}</button>
              </div>
              <label for="metronome-groups">{{ t('metronome.groups') }}</label>
              <input id="metronome-groups" :value="groupInput"
                @input="groupInput = ($event.target as HTMLInputElement).value"
                @change="changeGroups" placeholder="2+2+3" />
              <span class="m111-setting-label">{{ t('metronome.tempoUnit') }}</span>
              <div class="m111-unit-grid" role="group" :aria-label="t('metronome.tempoUnit')">
                <button v-for="unit in tempoUnits" :key="unit" type="button"
                  :aria-pressed="draftConfig.tempoUnit === unit" @click="changeTempoUnit(unit)">
                  {{ unitMarks[unit] }} <small>{{ t('metronome.units.' + unit) }}</small>
                </button>
              </div>
              <div class="metronome-master">
                <label for="metronome-master">{{ t('metronome.master') }} · {{ draftConfig.masterVolumePercent }}%</label>
                <input id="metronome-master" type="range" min="0" max="100" step="1"
                  :value="draftConfig.masterVolumePercent" @input="changeMaster(($event.target as HTMLInputElement).value)" />
              </div>
              <label for="metronome-beat-selection">{{ t('metronome.selectBeatForVolume') }}</label>
              <select id="metronome-beat-selection" :value="beatSelection"
                @change="beatSelection = Number(($event.target as HTMLSelectElement).value)">
                <option v-for="(_position, index) in draftConfig.positions" :key="index" :value="index">{{ index + 1 }}</option>
              </select>
              <label for="metronome-position-volume">{{ t('metronome.positionVolume', { number:beatSelection+1 }) }} · {{ draftConfig.positions[beatSelection]?.volumePercent }}%</label>
              <input id="metronome-position-volume" type="range" min="0" max="100" step="1"
                :value="draftConfig.positions[beatSelection]?.volumePercent"
                @input="changePositionVolume(($event.target as HTMLInputElement).value)" />
              <template v-for="accent in (['strong','medium','weak'] as const)" :key="accent">
                <label :for="'metronome-gain-' + accent">{{ t('metronome.accents.' + accent) }} · {{ draftConfig.accentVolumes[accent] }}%</label>
                <input :id="'metronome-gain-' + accent" type="range" min="0" max="100" step="1"
                  :value="draftConfig.accentVolumes[accent]"
                  @input="changeAccentVolume(accent, ($event.target as HTMLInputElement).value)" />
              </template>
              <label for="metronome-sub-gain">{{ t('metronome.subdivisionVolume') }} · {{ draftConfig.subdivisionVolumePercent }}%</label>
              <input id="metronome-sub-gain" type="range" min="0" max="100" step="1"
                :value="draftConfig.subdivisionVolumePercent"
                @input="changeSubdivisionVolume(($event.target as HTMLInputElement).value)" />
              <div class="m111-sheet-actions">
                <button type="button" :disabled="!dirty" @click="apply">{{ t('metronome.apply') }}</button>
                <button type="button" :disabled="!dirty && parseMetronomeBpm(draft) !== null" @click="discard">{{ t('metronome.discard') }}</button>
              </div>
              <button v-if="!confirmReset" type="button" class="m111-reset" @click="confirmReset = true">{{ t('metronome.resetStorage') }}</button>
              <div v-else class="metronome-confirm">
                <span>{{ t(metronomeUi.storageBlocked ? 'metronome.confirmResetCorrupt' : 'metronome.confirmReset') }}</span>
                <button type="button" @click="resetPreferences">{{ t('metronome.confirm') }}</button>
                <button type="button" @click="confirmReset = false">{{ t('metronome.cancel') }}</button>
              </div>
            </div>
          </div>
        </div>

        <div class="m111-sheet" :class="{ 'is-open': panel === 'presets' }"
          :aria-hidden="panel !== 'presets'" aria-labelledby="m111-presets-title">
          <div class="m111-sheet-head"><h3 id="m111-presets-title">{{ t('metronome.presets') }}</h3>
            <button type="button" :aria-label="t('metronome.closeSettings')" @click="closePanel">×</button></div>
          <div class="m111-sheet-scroll">
            <div class="metronome-presets">
              <label for="metronome-preset-list">{{ t('metronome.presets') }}</label>
              <select id="metronome-preset-list" :value="selectedPreset"
                @change="selectedPreset = ($event.target as HTMLSelectElement).value">
                <option value="">{{ t('metronome.selectPreset') }}</option>
                <option v-for="p in metronomeUi.presets" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              <button type="button" :disabled="!chosenPreset" @click="loadPreset">{{ t('metronome.loadPreset') }}</button>
              <label for="metronome-preset-name">{{ t('metronome.presetName') }}</label>
              <input id="metronome-preset-name" :value="presetName"
                @input="presetName = ($event.target as HTMLInputElement).value" maxlength="60" />
              <p class="m111-preset-review">{{ t('metronome.savingPreview') }}:
                {{ draft }} BPM · {{ draftConfig.numerator }}/{{ draftConfig.denominator }}
                · {{ t('metronome.units.' + draftConfig.tempoUnit) }} · {{ draftConfig.groups.join('+') }}
              </p>
              <div class="metronome-preset-actions">
                <button type="button" @click="savePreset">{{ t('metronome.savePreset') }}</button>
                <button type="button" :disabled="!chosenPreset" @click="renamePreset">{{ t('metronome.renamePreset') }}</button>
                <button type="button" :disabled="!chosenPreset" @click="confirmAction = 'replace'">{{ t('metronome.replacePreset') }}</button>
                <button type="button" :disabled="!chosenPreset" @click="confirmAction = 'delete'">{{ t('metronome.deletePreset') }}</button>
              </div>
              <div v-if="confirmAction" class="metronome-confirm">
                <span>{{ t('metronome.confirmPreset') }}</span>
                <button type="button" @click="confirmPreset">{{ t('metronome.confirm') }}</button>
                <button type="button" @click="confirmAction = null">{{ t('metronome.cancel') }}</button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>
