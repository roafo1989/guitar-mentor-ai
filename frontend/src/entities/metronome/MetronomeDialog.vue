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

const emit = defineEmits<{ close: []; minimize: [] }>();
const { t } = useI18n();
const state = ref<MetronomeState>(metronome.getState());
const active = ref<MetronomeConfig>(metronome.getConfig());
const draftConfig = ref<MetronomeConfig>(copyConfig(metronome.getPendingConfig() ?? active.value));
const draft = ref(String(state.value.bpm));
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
  if (document.activeElement !== bpmField.value) draft.value = String(next.bpm);
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
  const bpm = stepMetronomeBpm(state.value.bpm, step);
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
  const next = meterConfig(n, d as 2 | 4 | 8 | 16, state.value.bpm);
  next.masterVolumePercent = draftConfig.value.masterVolumePercent;
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
function apply(): boolean {
  const groups = parseGroups(groupInput.value, draftConfig.value.numerator);
  if (!groups) { configError.value = t('metronome.invalidGroups'); return false; }
  draftConfig.value.groups = groups;
  if (!validConfig(draftConfig.value)) {
    configError.value = t('metronome.invalidConfig'); return false;
  }
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
  dirty.value = false; configError.value = '';
}
function savePreset(): void {
  const name = presetName.value.trim();
  if (!name || name.length > 60 || metronomeUi.presets.length >= 20 ||
      !validConfig(draftConfig.value)) {
    configError.value = t('metronome.invalidPreset'); return;
  }
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
  } else if (validConfig(draftConfig.value)) {
    p.config = copyConfig(draftConfig.value);
  } else {
    configError.value = t('metronome.invalidConfig'); return;
  }
  confirmAction.value = null;
  store();
}
function resetPreferences(): void {
  if (resetMetronomePreferences()) discard();
  else configError.value = t('metronome.storeFailure');
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
    event.preventDefault(); event.stopPropagation(); emit('close'); return;
  }
  if (event.key !== 'Tab' || !dialog.value) return;
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0);
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
      <section ref="dialog" class="metronome-dialog" role="dialog" aria-modal="true"
        aria-labelledby="metronome-title" aria-describedby="metronome-description"
        tabindex="-1" @keydown="onKeydown">
        <div class="metronome-dialog-heading">
          <h2 id="metronome-title">{{ t('metronome.title') }}</h2>
          <button class="metronome-close" type="button" :aria-label="t('metronome.close')"
            :title="t('metronome.close')" @click="emit('close')">×</button>
        </div>
        <p id="metronome-description" class="metronome-note">{{ t('metronome.description') }}</p>
        <div class="metronome-tempo">
          <label for="metronome-bpm">{{ t('metronome.tempo') }}</label>
          <div class="metronome-tempo-controls">
            <button type="button" :aria-label="t('metronome.slower')" :disabled="state.bpm <= MIN_BPM" @click="stepTempo(-1)">−</button>
            <input id="metronome-bpm" ref="bpmField" :value="draft" @input="onTempoInput"
              type="number" inputmode="numeric" :min="MIN_BPM" :max="MAX_BPM" step="1"
              :aria-invalid="!!error && state.error === null"
              :aria-describedby="error ? 'metronome-error' : 'metronome-range'"
              @change="commitTempo" @keydown.enter.prevent="commitTempo" />
            <button type="button" :aria-label="t('metronome.faster')" :disabled="state.bpm >= MAX_BPM" @click="stepTempo(1)">+</button>
            <span class="metronome-bpm-unit">BPM · {{ t('metronome.units.' + draftConfig.tempoUnit) }}</span>
            <button type="button" class="metronome-tap" @click="tap">{{ t('metronome.tap') }}</button>
          </div>
          <small id="metronome-range">{{ MIN_BPM }}–{{ MAX_BPM }} BPM · {{ t('metronome.current', { bpm: state.bpm }) }}</small>
          <small v-if="tapStatus" role="status">{{ tapStatus }}</small>
          <p v-if="error" id="metronome-error" role="alert" class="metronome-error">{{ error }}</p>
        </div>

        <div class="metronome-meter-picker">
          <label for="metronome-numerator">{{ t('metronome.meter') }}</label>
          <select id="metronome-numerator" :value="meterN" @change="meterN = Number(($event.target as HTMLSelectElement).value); changeMeter()">
            <option v-for="n in 16" :key="n" :value="n">{{ n }}</option>
          </select>
          <span aria-hidden="true">/</span>
          <select :value="meterD" :aria-label="t('metronome.denominator')" @change="meterD = Number(($event.target as HTMLSelectElement).value) as 2 | 4 | 8 | 16; changeMeter()">
            <option v-for="d in denominators" :key="d" :value="d">{{ d }}</option>
          </select>
        </div>
        <div class="metronome-quick" :aria-label="t('metronome.quickMeters')">
          <button v-for="key in FAST_METERS" :key="key" type="button"
            :aria-pressed="draftConfig.numerator + '/' + draftConfig.denominator === key"
            @click="pickQuick(key)">{{ key }}</button>
        </div>

        <div class="metronome-count" :aria-label="draftConfig.numerator + '/' + draftConfig.denominator">
          <button v-for="(position, index) in draftConfig.positions" :key="index" type="button"
            class="metronome-beat"
            :class="['accent-' + position.accent, { 'is-active': state.status === 'running' && state.beat === index + 1, 'is-selected': beatSelection === index }]"
            :aria-label="t('metronome.beatAccent', { number: index + 1, accent: t('metronome.accents.' + position.accent) })"
            @click="cycleAccent(index)">
            <span>{{ index + 1 }}</span><small>{{ t('metronome.accents.' + position.accent) }}</small>
          </button>
        </div>
        <p v-if="silent" class="metronome-note">{{ t('metronome.silent') }}</p>

        <details class="metronome-details">
          <summary>{{ t('metronome.details') }}</summary>
          <div class="metronome-settings">
            <label for="metronome-groups">{{ t('metronome.groups') }}</label>
            <input id="metronome-groups" :value="groupInput" @input="groupInput = ($event.target as HTMLInputElement).value"
              @change="changeGroups" placeholder="2+2+3" />
            <label for="metronome-unit">{{ t('metronome.tempoUnit') }}</label>
            <select id="metronome-unit" :value="draftConfig.tempoUnit"
              @change="changeTempoUnit(($event.target as HTMLSelectElement).value)">
              <option v-for="unit in tempoUnits" :key="unit" :value="unit">{{ t('metronome.units.' + unit) }}</option>
            </select>
            <label for="metronome-subdivision">{{ t('metronome.subdivision') }}</label>
            <select id="metronome-subdivision" :value="draftConfig.subdivision"
              @change="changeSubdivision(($event.target as HTMLSelectElement).value)">
              <option v-for="n in widths" :key="n" :value="n">{{ n }}</option>
            </select>
            <label for="metronome-master">{{ t('metronome.master') }} · {{ draftConfig.masterVolumePercent }}%</label>
            <input id="metronome-master" type="range" min="0" max="100" step="1"
              :value="draftConfig.masterVolumePercent" @input="changeMaster(($event.target as HTMLInputElement).value)" />
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
            <div class="metronome-presets">
              <label for="metronome-preset-list">{{ t('metronome.presets') }}</label>
              <select id="metronome-preset-list" :value="selectedPreset" @change="selectedPreset = ($event.target as HTMLSelectElement).value">
                <option value="">{{ t('metronome.selectPreset') }}</option>
                <option v-for="p in metronomeUi.presets" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              <label for="metronome-preset-name">{{ t('metronome.presetName') }}</label>
              <input id="metronome-preset-name" :value="presetName" @input="presetName = ($event.target as HTMLInputElement).value" maxlength="60" />
              <div class="metronome-preset-actions">
                <button type="button" @click="savePreset">{{ t('metronome.savePreset') }}</button>
                <button type="button" :disabled="!chosenPreset" @click="loadPreset">{{ t('metronome.loadPreset') }}</button>
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
            <button v-if="metronomeUi.storageBlocked" type="button" @click="resetPreferences">{{ t('metronome.resetStorage') }}</button>
          </div>
        </details>
        <div class="metronome-apply">
          <button type="button" :disabled="!dirty" @click="apply">{{ t('metronome.apply') }}</button>
          <button type="button" :disabled="!dirty" @click="discard">{{ t('metronome.discard') }}</button>
          <small v-if="metronome.getPendingConfig()" role="status">{{ t('metronome.pending') }}</small>
        </div>
        <p v-if="configError || metronomeUi.storageError" role="alert" class="metronome-error">
          {{ configError || t('metronome.storeFailure') }}
        </p>
        <p class="metronome-state" role="status" aria-live="polite">{{ t('metronome.status.' + state.status) }}</p>
        <p v-if="state.error" role="alert" class="metronome-error">{{ t('metronome.unavailable') }}</p>
        <div class="metronome-actions">
          <button type="button" class="action-button"
            :aria-disabled="state.status === 'starting'" @click="togglePlayback">
            {{ state.status === 'running' ? t('metronome.stop') : t('metronome.start') }}
          </button>
          <button v-if="state.status === 'running'" type="button"
            class="recorder-button secondary-button" @click="emit('minimize')">{{ t('metronome.minimize') }}</button>
        </div>
        <p class="metronome-note">{{ t('metronome.warning') }}</p>
      </section>
    </div>
  </Teleport>
</template>
