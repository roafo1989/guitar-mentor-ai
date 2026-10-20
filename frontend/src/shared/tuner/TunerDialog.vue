<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../audio/audio-session';
import { STRINGS, cents, detectPitch, noteName, stablePitch } from './pitch';
import './tuner.css';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: [] }>();
const { t } = useI18n();
const dialog = ref<HTMLElement | null>(null);
const selected = ref(0);
const phase = ref<'idle' | 'requesting' | 'listening'>('idle');
const message = ref('tuner.ready');
const error = ref('');
const frequency = ref<number | null>(null);
const target = computed(() => STRINGS[selected.value]);
const deviation = computed(() => frequency.value === null ? null : cents(frequency.value, target.value.hz));
const reliable = computed(() => phase.value === 'listening' && deviation.value !== null && Math.abs(deviation.value) <= 200);
const status = computed(() => {
  if (!reliable.value || deviation.value === null) return message.value;
  if (Math.abs(deviation.value) <= 5) return 'tuner.inTune';
  return deviation.value < 0 ? 'tuner.flat' : 'tuner.sharp';
});
const position = computed(() => 50 + Math.max(-50, Math.min(50, deviation.value ?? 0)));
const token = Symbol('tuner');
const history: number[] = [];
let generation = 0;
let stream: MediaStream | null = null;
let context: AudioContext | null = null;
let source: MediaStreamAudioSourceNode | null = null;
let analyser: AnalyserNode | null = null;
let frame: number | null = null;

function resetPitch(): void {
  frequency.value = null;
  history.length = 0;
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
      if (now - last >= 120) {
        last = now;
        analyser.getFloatTimeDomainData(samples);
        const candidate = detectPitch(samples, context.sampleRate);
        frequency.value = stablePitch(history, candidate);
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
  selected.value = index;
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
  if (!open) stop();
  else { await nextTick(); dialog.value?.focus(); }
});
onBeforeUnmount(stop);
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="tuner-overlay" @mousedown.self="close">
      <section ref="dialog" role="dialog" aria-modal="true" class="tuner-modal"
        :aria-label="t('tuner.title')" tabindex="-1" @keydown="handleKey">
        <div class="tuner-heading">
          <h2>{{ t('tuner.title') }}</h2>
          <button type="button" class="tuner-close" :title="t('tuner.close')" :aria-label="t('tuner.close')" @click="close">×</button>
        </div>
        <p>{{ t('tuner.instructions') }}</p>
        <div role="group" :aria-label="t('tuner.strings')" class="tuner-strings">
          <button v-for="(string, index) in STRINGS" :key="string.number" type="button"
            :class="{ selected: index === selected }" :aria-pressed="index === selected"
            :aria-label="t('tuner.string', { number: string.number, note: string.note })"
            @click="choose(index)">
            <small>{{ string.number }}</small><strong>{{ string.note }}</strong>
          </button>
        </div>
        <div class="tuner-screen">
          <p>{{ t('tuner.target') }}: <strong>{{ target.note }}</strong> · {{ target.hz.toFixed(1) }} Hz</p>
          <div class="tuner-note"><small>{{ t('tuner.detected') }}</small>
            <strong>{{ reliable && frequency !== null ? noteName(frequency) : '—' }}</strong>
          </div>
          <p role="status" aria-live="polite" class="tuner-status" :class="{ tuned: reliable && Math.abs(deviation ?? 999) <= 5 }">
            {{ t(status) }}
          </p>
          <div class="tuner-meter" role="meter" :aria-label="t('tuner.deviation')"
            aria-valuemin="-50" aria-valuemax="50"
            :aria-valuenow="reliable ? Math.round(deviation ?? 0) : undefined"
            :aria-valuetext="reliable ? t('tuner.cents', { value: Math.round(deviation ?? 0) }) : t('tuner.noReading')">
            <span class="tuner-zero"></span>
            <span v-if="reliable" class="tuner-needle" :style="{ left: position + '%' }"></span>
          </div>
          <div class="tuner-scale"><span>−50</span><span>0</span><span>+50</span></div>
          <p>{{ reliable ? t('tuner.cents', { value: Math.round(deviation ?? 0) }) : t('tuner.noReading') }}</p>
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
