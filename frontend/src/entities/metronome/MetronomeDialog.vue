<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { metronome, type MetronomeState } from '../../shared/audio/metronome';
import { MAX_BPM, MIN_BPM, parseMetronomeBpm, stepMetronomeBpm } from './metronome-input';

const emit = defineEmits<{ close: []; minimize: [] }>();
const { t } = useI18n();
const state = ref<MetronomeState>(metronome.getState());
const draft = ref(String(state.value.bpm));
const error = ref('');
const dialog = ref<HTMLElement | null>(null);
const bpmField = ref<HTMLInputElement | null>(null);
let unsubscribe: (() => void) | null = null;
let previousOverflow = '';

function commitTempo(): boolean {
  const parsed = parseMetronomeBpm(draft.value);
  if (parsed === null) {
    error.value = t('metronome.invalidBpm');
    return false;
  }
  error.value = '';
  metronome.setBpm(parsed);
  draft.value = String(parsed);
  return true;
}

function onTempoInput(event: Event): void {
  // Read the raw DOM string. Vue's v-model on type=number would silently
  // coerce it to Number and previously caused .trim() to throw on Start.
  draft.value = (event.target as HTMLInputElement).value;
  const parsed = parseMetronomeBpm(draft.value);
  if (parsed === null) {
    error.value = t('metronome.invalidBpm');
    return; // Do not modify the last valid engine BPM.
  }
  error.value = '';
  // Update the metronome while the student types, including during playback.
  metronome.setBpm(parsed);
}

function stepTempo(step: -1 | 1): void {
  const bpm = stepMetronomeBpm(state.value.bpm, step);
  draft.value = String(bpm);
  error.value = '';
  metronome.setBpm(bpm);
}

function start(): void {
  if (!commitTempo()) return;
  // Must be called directly in this click handler to satisfy browser audio permission.
  void metronome.start();
}

function stop(): void {
  metronome.stop();
}

function requestClose(): void {
  emit('close');
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    requestClose();
    return;
  }
  if (event.key !== 'Tab' || !dialog.value) return;
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]),input:not([disabled]),[tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0);
  if (!focusable.length) {
    event.preventDefault();
    dialog.value.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

onMounted(async () => {
  unsubscribe = metronome.subscribe(next => {
    state.value = { ...next };
    if (document.activeElement !== bpmField.value) draft.value = String(next.bpm);
    if (next.error) error.value = '';
  });
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = 'hidden';
  await nextTick();
  bpmField.value?.focus();
});

onBeforeUnmount(() => {
  unsubscribe?.();
  document.body.style.overflow = previousOverflow;
  // Only unsubscribe. Closing explicitly stops in closeMetronome(); minimizing must not.
});
</script>

<template>
  <Teleport to="body">
    <div class="metronome-backdrop" @click.self="requestClose">
      <section ref="dialog" class="metronome-dialog" role="dialog" aria-modal="true"
        aria-labelledby="metronome-title" aria-describedby="metronome-description"
        tabindex="-1" @keydown="onKeydown">
        <div class="metronome-dialog-heading">
          <h2 id="metronome-title">{{ t('metronome.title') }}</h2>
          <button class="metronome-close" type="button"
            :aria-label="t('metronome.close')" :title="t('metronome.close')" @click="requestClose">×</button>
        </div>
        <p id="metronome-description" class="metronome-note">{{ t('metronome.description') }}</p>
        <div class="metronome-tempo">
          <label for="metronome-bpm">{{ t('metronome.tempo') }}</label>
          <div class="metronome-tempo-controls">
            <button type="button" :aria-label="t('metronome.slower')" :disabled="state.bpm <= MIN_BPM"
              @click="stepTempo(-1)">−</button>
            <input id="metronome-bpm" ref="bpmField" :value="draft" @input="onTempoInput"
              type="number" inputmode="numeric" :min="MIN_BPM" :max="MAX_BPM" step="1"
              :aria-invalid="!!error && state.error === null" :aria-describedby="error ? 'metronome-error' : 'metronome-range'"
              @change="commitTempo" @keydown.enter.prevent="commitTempo" />
            <button type="button" :aria-label="t('metronome.faster')" :disabled="state.bpm >= MAX_BPM"
              @click="stepTempo(1)">+</button>
            <span class="metronome-bpm-unit">BPM</span>
          </div>
          <small id="metronome-range">{{ MIN_BPM }}–{{ MAX_BPM }} BPM · {{ t('metronome.current', { bpm: state.bpm }) }}</small>
          <p v-if="error" id="metronome-error" role="alert" class="metronome-error">{{ error }}</p>
        </div>
        <div class="metronome-count" aria-label="4/4">
          <span v-for="number in 4" :key="number" class="metronome-beat"
            :class="{ 'is-active': state.status === 'running' && state.beat === number, 'is-accent': number === 1 }"
            :aria-label="t('metronome.beat', { number })">{{ number }}</span>
        </div>
        <p class="metronome-state" role="status" aria-live="polite">
          {{ t('metronome.status.' + state.status) }}
        </p>
        <p v-if="state.error" role="alert" class="metronome-error">
          {{ t('metronome.unavailable') }}
        </p>
        <div class="metronome-actions">
          <button type="button" class="action-button" :disabled="state.status === 'starting'"
            @click="state.status === 'running' ? stop() : start()">
            {{ state.status === 'running' ? t('metronome.stop') : t('metronome.start') }}
          </button>
          <button v-if="state.status === 'running'" type="button"
            class="recorder-button secondary-button" @click="emit('minimize')">
            {{ t('metronome.minimize') }}
          </button>
        </div>
        <p class="metronome-note">{{ t('metronome.warning') }}</p>
      </section>
    </div>
  </Teleport>
</template>
