<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../audio/audio-session';
import { detectPitch, noteName } from './pitch';
import { TUNING_IDS, TUNINGS, readTuningPreference, saveTuningPreference, type TuningId } from './tuning-profiles';
import { autoStringIndex, newPitchTrack, resetPitchTrack, displayPitch, newTuneConfirmation, resetTuneConfirmation, confirmTunedPitch } from './tuner-session';
import { isolateDialogFocus } from './modal-focus';
import { tunerReading } from './tuner-reading';
import headstockArtUrl from './approved-headstock.webp?url';
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
// Confirmation depends on multiple *fresh* candidate readings, never only
// on the current pitch and its rolling median.
const tunedStable = ref(false);
const tuningId = ref<TuningId>(readTuningPreference());
const tuning = computed(() => TUNINGS[tuningId.value]);
const strings = computed(() => tuning.value.strings);
const target = computed(() => selected.value === null ? null : strings.value[selected.value]);
// The approved natural-wood asset and all real, accessible controls share
// one scaled 440×520 coordinate system. Physical string order remains 6..1.
const pegs = [
  { x: 65, y: 320 }, { x: 65, y: 218 }, { x: 65, y: 111 },
  { x: 375, y: 111 }, { x: 375, y: 218 }, { x: 375, y: 320 },
] as const;
// The asset already contains all six physical strings. These overlay
// paths only glow when a string is selected, never replace the real buttons.
const stringPaths = [
  'M181 495 L181 445 L173 320',
  'M201 495 L201 445 L173 218',
  'M214 495 L214 445 L173 111',
  'M227 495 L227 445 L267 111',
  'M240 495 L240 445 L267 218',
  'M260 495 L260 445 L267 320',
] as const;
// Valid pitch/cents/meter are independent of green confirmation. In
// particular, the needle remains visible while crossing the target.
const reading = computed(() => phase.value === 'listening' && target.value
  ? tunerReading(frequency.value, target.value.hz) : null);
const note = computed(() => frequency.value === null ? '—' : noteName(frequency.value));
const held = ref(false);
const status = computed(() => reading.value
  ? held.value ? 'tuner.holding' :
    reading.value.status === 'inTune' && !isTuned.value
      ? 'tuner.stabilizing' : 'tuner.' + reading.value.status
  : phase.value === 'listening' && frequency.value !== null && mode.value === 'auto'
    ? 'tuner.autoUncertain' : message.value);
const position = computed(() => 50 + (reading.value?.meterValue ?? 0));
const isTuned = computed(() => phase.value === 'listening' && !held.value &&
  reading.value?.status === 'inTune' && tunedStable.value);
const token = Symbol('tuner');
const tracking = newPitchTrack();
const tuneConfirmation = newTuneConfirmation();
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
  tunedStable.value = false;
  resetTuneConfirmation(tuneConfirmation);
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
        frequency.value = displayPitch(tracking, candidate, now);
        held.value = candidate === null && frequency.value !== null;
        if (mode.value === 'auto') {
          selected.value = autoStringIndex(frequency.value, selected.value, strings.value);
        }
        const freshTarget = selected.value === null ? null : strings.value[selected.value];
        tunedStable.value = confirmTunedPitch(
          tuneConfirmation, tracking.frequency !== null && !held.value ? candidate : null,
          freshTarget?.hz ?? null,
        );
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
function changeTuning(event: Event): void {
  const id = (event.target as HTMLSelectElement).value;
  if (!TUNING_IDS.some(item => item === id) || id === tuningId.value) return;
  tuningId.value = id as TuningId;
  saveTuningPreference(tuningId.value);
  // Auto loses its target; Manual keeps the physical string but never
  // carries over the old pitch/hold/green status from another tuning.
  resetPitch();
  if (phase.value === 'listening') message.value = 'tuner.stabilizing';
}
function choose(index: number): void {
  mode.value = 'manual';
  selected.value = index;
  resetPitch();
  if (phase.value === 'listening') message.value = 'tuner.stabilizing';
}
function chooseAuto(): void {
  mode.value = 'auto';
  // Never inherit a possibly polyphonic pitch from manual mode. Acquire a
  // fresh set of three monophonic frames before selecting the target.
  resetPitch();
  if (phase.value === 'listening') message.value = 'tuner.stabilizing';
}
function handleKey(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault();
    close();
  }
  if (event.key !== 'Tab' || !dialog.value) return;
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), select:not([disabled])'));
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
    <!-- Backdrop is deliberately non-dismissive during microphone work. -->
    <div v-if="open" class="tuner-overlay">
      <section ref="dialog" role="dialog" aria-modal="true" class="tuner-modal"
        :aria-label="t('tuner.title')" tabindex="-1" @keydown="handleKey">
        <header class="tuner-heading">
          <div class="tuner-ident">
            <span class="tuner-kicker">GUITAR MENTOR <span aria-hidden="true">/</span> TOOLS</span>
            <h2>{{ t('tuner.title') }}</h2>
          </div>
          <button type="button" class="tuner-close" :title="t('tuner.close')"
            :aria-label="t('tuner.close')" @click="close" aria-keyshortcuts="Escape">×</button>
        </header>

        <!-- Primary microphone control must be visible without scrolling on phones. -->
        <div class="tuner-controls">
          <button v-if="phase === 'idle'" type="button" class="tuner-action" @click="start">
            <span aria-hidden="true" class="tuner-action-dot"></span>{{ t('tuner.start') }}
          </button>
          <button v-else type="button" class="tuner-action tuner-stop" @click="stop">
            {{ t('tuner.stop') }}
          </button>
        </div>

        <div class="tuner-toolbar">
          <label class="tuner-tuning-picker">
            <span>{{ t('tuner.tuning') }}</span>
            <select :value="tuningId" :aria-label="t('tuner.tuning')" @change="changeTuning">
              <option v-for="id in TUNING_IDS" :key="id" :value="id">{{ t(TUNINGS[id].labelKey) }}</option>
            </select>
          </label>
          <div class="tuner-auto-group">
            <span>{{ t(mode === 'auto' ? 'tuner.autoMode' : 'tuner.manualMode') }}</span>
            <button type="button" class="tuner-auto-button" :class="{ selected: mode === 'auto' }"
              :aria-pressed="mode === 'auto'" @click="chooseAuto">{{ t('tuner.auto') }}</button>
          </div>
        </div>

        <div class="tuner-readout-panel">
          <div class="tuner-readout">
            <div class="tuner-small-label">{{ t('tuner.detected') }}</div>
            <div class="tuner-note" :class="{ 'is-tuned': isTuned }">{{ note }}</div>
            <div class="tuner-readout-target">
              {{ t('tuner.target') }} <strong>{{ target?.note ?? '—' }}</strong>
              <span v-if="target">{{ target.hz.toFixed(1) }} Hz</span>
            </div>
          </div>
          <div class="tuner-meter-wrap">
            <div class="tuner-meter" :role="reading ? 'meter' : undefined"
              :aria-hidden="reading ? undefined : true"
              :aria-label="reading ? t('tuner.deviation') : undefined"
              :aria-valuemin="reading ? -50 : undefined"
              :aria-valuemax="reading ? 50 : undefined"
              :aria-valuenow="reading?.meterValue ?? undefined"
              :aria-valuetext="reading ? t('tuner.cents', { value: Math.round(reading.cents) }) : undefined">
              <span class="tuner-zero" aria-hidden="true"></span>
              <span v-if="reading" class="tuner-needle" :class="{ 'is-tuned': isTuned, 'is-held': held }"
                :style="{ left: position + '%' }" aria-hidden="true"></span>
            </div>
            <div class="tuner-scale" aria-hidden="true"><span>−50</span><span>0</span><span>+50</span></div>
            <div class="tuner-cents">{{ reading ? t('tuner.cents', { value: Math.round(reading.cents) }) : t('tuner.noReading') }}</div>
          </div>
          <p role="status" aria-live="polite" class="tuner-status"
            :class="{ tuned: isTuned, held: held }">{{ t(status) }}</p>
        </div>
        <div class="tuner-headstock-panel">
          <div class="tuner-headstock" role="group" :aria-label="t('tuner.strings')">
            <svg viewBox="0 0 440 520" class="tuner-headstock-art" aria-hidden="true">
              <!-- Art extracted from the exact owner-approved frontal design:
                   natural irregular wood grain, authentic 3+3 metal tuners,
                   truss-rod cover, six strings, nut and fingerboard. -->
              <image :href="headstockArtUrl" x="55" y="0"
                width="290" height="495" preserveAspectRatio="none"
                class="tuner-headstock-photo" />
              <path v-for="(string, index) in strings" :key="'wire-' + string.number"
                :d="stringPaths[index]" class="tuner-wire"
                :class="{ active: selected === index, tuned: selected === index && isTuned }"
                :stroke-width="2.7 - index * .18" />
            </svg>
            <button v-for="(string, index) in strings" :key="string.number" type="button"
              class="tuner-peg" :class="{ active: selected === index, tuned: selected === index && isTuned }"
              :style="{ left: (pegs[index].x / 440 * 100) + '%', top: (pegs[index].y / 520 * 100) + '%' }"
              :aria-pressed="selected === index"
              :aria-label="t('tuner.string', { number: string.number, note: string.note })"
              :title="t('tuner.string', { number: string.number, note: string.note })"
              @click="choose(index)">
              <small>{{ string.number }}</small><strong>{{ string.note.replace(/[0-9]/g, '') }}</strong><span class="tuner-peg-note">{{ string.note }}</span>
            </button>
          </div>
          <p class="tuner-hint">{{ t('tuner.manualHint') }}</p>
        </div>

        <p v-if="error" role="alert" class="tuner-error">{{ t(error) }}</p>
        <div class="tuner-microphone" :class="{ 'is-live': phase === 'listening' }"
          role="status" aria-live="off">
          <span class="tuner-microphone-icon" aria-hidden="true"></span>
          {{ t(phase === 'listening' ? 'tuner.microphoneOn' :
                phase === 'requesting' ? 'tuner.requesting' : 'tuner.ready') }}
        </div>

        <p class="tuner-privacy">{{ t('tuner.privacy') }}</p>
      </section>
    </div>
  </Teleport>
</template>
