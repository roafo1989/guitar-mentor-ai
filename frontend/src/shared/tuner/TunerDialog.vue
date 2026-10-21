<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../audio/audio-session';
import { STRINGS, detectPitch, noteName } from './pitch';
import { autoStringIndex, newPitchTrack, resetPitchTrack, trackPitch } from './tuner-session';
import { isolateDialogFocus } from './modal-focus';
import { tunerReading } from './tuner-reading';
import './tuner.css';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: [] }>();
const { t } = useI18n();
const dialog = ref<HTMLElement | null>(null);
const mode = ref<'auto' | 'manual'>('auto');
const selected = ref<number | null>(null);
const phase = ref<'idle' | 'requesting' | 'listening'>('idle');
const message = ref('tuner.ready');
const error = ref('');
const frequency = ref<number | null>(null);
const target = computed(() => selected.value === null ? null : STRINGS[selected.value]);
// An unrecognized (or heavily detuned) pitch must not silently choose a target.
const reading = computed(() => phase.value === 'listening' && target.value
  ? tunerReading(frequency.value, target.value.hz) : null);
const note = computed(() => frequency.value === null ? '—' : noteName(frequency.value));
const held = ref(false);
const status = computed(() => reading.value
  ? held.value ? 'tuner.holding' : 'tuner.' + reading.value.status
  : phase.value === 'listening' && frequency.value !== null && mode.value === 'auto'
    ? 'tuner.autoUncertain' : message.value);
const position = computed(() => 50 + (reading.value?.meterValue ?? 0));
const token = Symbol('tuner');
const tracking = newPitchTrack();
let generation = 0;
let stream: MediaStream | null = null;
let context: AudioContext | null = null;
let source: MediaStreamAudioSourceNode | null = null;
let analyser: AnalyserNode | null = null;
let frame: number | null = null;
let releaseFocusIsolation: (() => void) | null = null;

function clearFocusIsolation(): void {
  releaseFocusIsolation?.();
  releaseFocusIsolation = null;
}

function resetPitch(): void {
  resetPitchTrack(tracking);
  frequency.value = null;
  held.value = false;
  if (mode.value === 'auto') selected.value = null;
}
function stop(): void {
  ++generation;
  if (frame !== null) cancelAnimationFrame(frame);
  frame = null;
  source?.disconnect();
  analyser?.disconnect();
  source = null;
  analyser = null;
  const oldContext = context;
  context = null;
  if (oldContext) void oldContext.close().catch(() => undefined);
  stream?.getTracks().forEach(track => { track.onended = null; track.stop(); });
  stream = null;
  releaseAudio(token);
  phase.value = 'idle';
  message.value = 'tuner.ready';
  resetPitch();
}
function close(): void {
  stop();
  emit('close');
}
function microphoneError(cause: unknown): string {
  const name = cause instanceof Error ? cause.name : '';
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') return 'tuner.denied';
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'tuner.missing';
  if (name === 'NotReadableError' || name === 'TrackStartError') return 'tuner.busy';
  return 'tuner.failed';
}
async function start(): Promise<void> {
  if (phase.value !== 'idle') return;
  error.value = '';
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia ||
    typeof AudioContext === 'undefined') {
    error.value = 'tuner.unsupported';
    return;
  }
  if (!acquireAudio('tuner', token, stop)) {
    error.value = 'tuner.recorderActive';
    return;
  }
  // #87 can listen for this event to stop a running metronome.
  window.dispatchEvent(new CustomEvent('guitar-mentor:tool-activated', { detail: { tool: 'tuner' } }));
  const run = ++generation;
  phase.value = 'requesting';
  message.value = 'tuner.requesting';
  resetPitch();
  try {
    const obtained = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
      video: false,
    });
    // Permission prompts can resolve *after* Close / Cancel.
    if (generation !== run || !props.open) {
      obtained.getTracks().forEach(track => track.stop());
      return;
    }
    stream = obtained;
    context = new AudioContext();
    source = context.createMediaStreamSource(obtained);
    analyser = context.createAnalyser();
    analyser.fftSize = 8192;
    analyser.smoothingTimeConstant = 0;
    source.connect(analyser); // No connection to speakers; prevents feedback.
    if (context.state === 'suspended') await context.resume();
    if (generation !== run || !props.open || !analyser || !context) return;
    obtained.getAudioTracks().forEach(track => {
      track.onended = () => {
        if (generation === run) {
          stop();
          error.value = 'tuner.disconnected';
        }
      };
    });
    const samples = new Float32Array(analyser.fftSize);
    let last = 0;
    phase.value = 'listening';
    message.value = 'tuner.noSignal';
    const tick = (now: number) => {
      if (generation !== run || !analyser || !context) return;
      if (now - last >= 100) {
        last = now;
        analyser.getFloatTimeDomainData(samples);
        // Auto mode must not confirm tuning when a competing pitch is detected.
        // Manual mode remains available for difficult or severely detuned notes.
        const candidate = detectPitch(samples, context.sampleRate, mode.value === 'auto');
        frequency.value = trackPitch(tracking, candidate, now);
        held.value = candidate === null && frequency.value !== null;
        if (mode.value === 'auto') {
          selected.value = autoStringIndex(frequency.value, selected.value);
        }
        message.value = candidate === null ? 'tuner.noSignal' :
          frequency.value === null ? 'tuner.stabilizing' : 'tuner.wrongString';
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
  } catch (cause) {
    if (generation === run) {
      stop();
      error.value = microphoneError(cause);
    }
  }
}
function choose(index: number): void {
  mode.value = 'manual';
  selected.value = index;
  resetPitch();
}
function chooseAuto(): void {
  mode.value = 'auto';
  // Never inherit a possibly polyphonic pitch from manual mode. Acquire a
  // fresh set of three monophonic frames before selecting the target.
  resetPitch();
}
function handleKey(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault();
    close();
  }
  if (event.key !== 'Tab' || !dialog.value) return;
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>('button:not([disabled])'));
  if (!focusable.length) return;
  const first = focusable[0], last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus();
  } else if (document.activeElement === dialog.value) {
    event.preventDefault(); first.focus();
  }
}
watch(() => props.open, async open => {
  if (!open) {
    clearFocusIsolation();
    stop();
    return;
  }
  // An earlier permission or device error must not survive a new dialog session.
  error.value = '';
  mode.value = 'auto';
  resetPitch();
  await nextTick();
  if (!props.open || !dialog.value) return;
  clearFocusIsolation();
  // App.vue exclusively controls app-shell.inert. This guard only traps
  // focus inside the Teleported dialog; it never rewrites the shell state.
  releaseFocusIsolation = isolateDialogFocus(dialog.value);
}, { flush: 'post' });
onBeforeUnmount(() => {
  clearFocusIsolation();
  stop();
});
</script>

<template>
  <Teleport to="body">
    <!-- Backdrop clicks are intentionally ignored: don't interrupt tuning. -->
    <div v-if="open" class="tuner-overlay">
      <section ref="dialog" role="dialog" aria-modal="true" class="tuner-modal"
        :aria-label="t('tuner.title')" tabindex="-1" @keydown="handleKey">
        <div class="tuner-heading">
          <h2>{{ t('tuner.title') }}</h2>
          <button type="button" class="tuner-close" :title="t('tuner.close')" :aria-label="t('tuner.close')" @click="close">×</button>
        </div>
        <p>{{ t('tuner.instructions') }}</p>
        <div class="tuner-mode">
          <button type="button" class="tuner-auto-button" :class="{ selected: mode === 'auto' }"
            :aria-pressed="mode === 'auto'" @click="chooseAuto">{{ t('tuner.auto') }}</button>
          <span>{{ t('tuner.manualHint') }}</span>
        </div>
        <div role="group" :aria-label="t('tuner.strings')" class="tuner-strings">
          <button v-for="(string, index) in STRINGS" :key="string.number" type="button"
            :class="{ selected: index === selected }" :aria-pressed="index === selected"
            :aria-label="t('tuner.string', { number: string.number, note: string.note })"
            @click="choose(index)">
            <small>{{ string.number }}</small><strong>{{ string.note }}</strong>
          </button>
        </div>
        <div class="tuner-screen">
          <p>{{ t('tuner.target') }}: <strong>{{ target?.note ?? '—' }}</strong>
            <span v-if="target"> · {{ target.hz.toFixed(1) }} Hz</span>
          </p>
          <div class="tuner-note"><small>{{ t('tuner.detected') }}</small>
            <strong>{{ note }}</strong>
          </div>
          <p role="status" aria-live="polite" class="tuner-status" :class="{ tuned: reading?.status === 'inTune' && !held }">
            {{ t(status) }}
          </p>
          <div class="tuner-meter" :role="reading ? 'meter' : undefined"
            :aria-hidden="reading ? undefined : true"
            :aria-label="reading ? t('tuner.deviation') : undefined"
            :aria-valuemin="reading ? -50 : undefined"
            :aria-valuemax="reading ? 50 : undefined"
            :aria-valuenow="reading?.meterValue ?? undefined"
            :aria-valuetext="reading ? t('tuner.cents', { value: Math.round(reading.cents) }) : undefined">
            <span class="tuner-zero"></span>
            <span v-if="reading" class="tuner-needle" :style="{ left: position + '%' }"></span>
          </div>
          <div class="tuner-scale"><span>−50</span><span>0</span><span>+50</span></div>
          <p>{{ reading ? t('tuner.cents', { value: Math.round(reading.cents) }) : t('tuner.noReading') }}</p>
        </div>
        <p v-if="error" role="alert" class="attempt-error">{{ t(error) }}</p>
        <div class="tuner-controls">
          <button v-if="phase === 'idle'" type="button" class="action-button" @click="start">{{ t('tuner.start') }}</button>
          <button v-else type="button" class="action-button secondary-button" @click="stop">{{ t('tuner.stop') }}</button>
        </div>
        <p class="tuner-privacy">{{ t('tuner.privacy') }}</p>
      </section>
    </div>
  </Teleport>
</template>
