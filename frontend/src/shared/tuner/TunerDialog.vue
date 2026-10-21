<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../audio/audio-session';
import { cents, detectPitch, noteName } from './pitch';
import { TUNING_IDS, TUNINGS, readTuningPreference, saveTuningPreference, type TuningId } from './tuning-profiles';
import { autoStringIndex, newPitchTrack, resetPitchTrack, displayPitch } from './tuner-session';
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
const lastCandidate = ref<number | null>(null);
// Independent from the live display value: three-frame confirmation is
// required before showing "in tune", even during a glissando.
const confirmedFrequency = ref<number | null>(null);
const tuningId = ref<TuningId>(readTuningPreference());
const tuning = computed(() => TUNINGS[tuningId.value]);
const strings = computed(() => tuning.value.strings);
const target = computed(() => selected.value === null ? null : strings.value[selected.value]);
const pegs = [
  { x: 54, y: 246 }, { x: 54, y: 166 }, { x: 54, y: 86 },
  { x: 306, y: 86 }, { x: 306, y: 166 }, { x: 306, y: 246 },
] as const;
// Each path stays tied to the physical string number, not its pitch class.
const stringPaths = [
  'M148 356 L128 246', 'M160 356 L128 166', 'M172 356 L128 86',
  'M188 356 L232 86', 'M200 356 L232 166', 'M212 356 L232 246',
] as const;
// A live reading inside tolerance is not a *confirmed* tuned string until
// both the latest candidate and the three-frame median are also inside.
// In particular, a glissando passing through the target must not flash green.
const unconfirmedInTune = computed(() => phase.value === 'listening' && target.value !== null &&
  frequency.value !== null && lastCandidate.value !== null &&
  Math.abs(cents(frequency.value, target.value.hz)) <= 5 &&
  (confirmedFrequency.value === null ||
   Math.abs(cents(confirmedFrequency.value, target.value.hz)) > 5 ||
   Math.abs(cents(lastCandidate.value, target.value.hz)) > 5));
// An unrecognized (or heavily detuned) pitch must not silently choose a target.
const reading = computed(() => {
  if (phase.value !== 'listening' || !target.value || unconfirmedInTune.value) return null;
  return tunerReading(frequency.value, target.value.hz);
});
const note = computed(() => frequency.value === null ? '—' : noteName(frequency.value));
const held = ref(false);
const status = computed(() => unconfirmedInTune.value ? 'tuner.stabilizing' : reading.value
  ? held.value ? 'tuner.holding' : 'tuner.' + reading.value.status
  : phase.value === 'listening' && frequency.value !== null && mode.value === 'auto'
    ? 'tuner.autoUncertain' : message.value);
const position = computed(() => 50 + (reading.value?.meterValue ?? 0));
const isTuned = computed(() => phase.value === 'listening' && !held.value &&
  reading.value?.status === 'inTune' && lastCandidate.value !== null &&
  target.value !== null && confirmedFrequency.value !== null &&
  Math.abs(cents(lastCandidate.value, target.value.hz)) <= 5 &&
  Math.abs(cents(confirmedFrequency.value, target.value.hz)) <= 5);
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
  lastCandidate.value = null;
  confirmedFrequency.value = null;
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
        lastCandidate.value = candidate;
        frequency.value = displayPitch(tracking, candidate, now);
        confirmedFrequency.value = tracking.frequency;
        held.value = candidate === null && frequency.value !== null;
        if (mode.value === 'auto') {
          selected.value = autoStringIndex(frequency.value, selected.value, strings.value);
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

        <div class="tuner-stage">
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
          <div class="tuner-headstock" role="group" :aria-label="t('tuner.strings')">
            <svg viewBox="0 0 360 385" class="tuner-headstock-art" aria-hidden="true">
              <defs>
                <linearGradient id="gm-tuner-wood" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#29282b" />
                  <stop offset=".28" stop-color="#504440" />
                  <stop offset=".65" stop-color="#403631" />
                  <stop offset="1" stop-color="#211f22" />
                </linearGradient>
                <linearGradient id="gm-tuner-metal" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#fff4da" />
                  <stop offset=".48" stop-color="#b4a99c" />
                  <stop offset="1" stop-color="#494c50" />
                </linearGradient>
              </defs>
              <path d="M145 385 V334 Q111 320 111 277 V91 Q111 61 144 49 L180 23
                L216 49 Q249 61 249 91 V277 Q249 320 215 334 V385Z"
                fill="url(#gm-tuner-wood)" stroke="#827265" stroke-width="2" />
              <path d="M151 385 V331 Q122 317 122 272 V94 Q122 71 154 51 L180 36
                L206 51 Q238 71 238 94 V272 Q238 317 209 331 V385"
                fill="none" stroke="#c3a27e" stroke-opacity=".18" stroke-width="2" />
              <path d="M180 51 V330 M154 83 V302 M206 83 V302" stroke="#a4907d"
                stroke-opacity=".10" stroke-width="1.5" />
              <g v-for="(peg, index) in pegs" :key="'fixture-' + index">
                <line :x1="peg.x < 180 ? 129 : 231" :x2="peg.x < 180 ? 84 : 276"
                  :y1="peg.y" :y2="peg.y" stroke="#bfb7ab" stroke-width="10" stroke-linecap="round" />
                <circle :cx="peg.x < 180 ? 129 : 231" :cy="peg.y" r="13"
                  fill="#36383a" stroke="url(#gm-tuner-metal)" stroke-width="5" />
                <circle :cx="peg.x < 180 ? 129 : 231" :cy="peg.y" r="3" fill="#d8c3a2" />
              </g>
              <path v-for="(string, index) in strings" :key="'wire-' + string.number"
                :d="stringPaths[index]" class="tuner-wire"
                :class="{ active: selected === index, tuned: selected === index && isTuned }"
                :stroke-width="2.8 - index * .29" />
              <path d="M142 348 H218" stroke="#d0b99c" stroke-width="8" stroke-linecap="round" />
              <path d="M142 354 H218" stroke="#f1e0c3" stroke-opacity=".45" stroke-width="1.5" />
            </svg>
            <button v-for="(string, index) in strings" :key="string.number" type="button"
              class="tuner-peg" :class="{ active: selected === index, tuned: selected === index && isTuned }"
              :style="{ left: (pegs[index].x / 360 * 100) + '%', top: (pegs[index].y / 385 * 100) + '%' }"
              :aria-pressed="selected === index"
              :aria-label="t('tuner.string', { number: string.number, note: string.note })"
              :title="t('tuner.string', { number: string.number, note: string.note })"
              @click="choose(index)">
              <small>{{ string.number }}</small><strong>{{ string.note }}</strong>
            </button>
          </div>
          <p class="tuner-hint">{{ t('tuner.manualHint') }}</p>
        </div>

        <p v-if="error" role="alert" class="tuner-error">{{ t(error) }}</p>
        <div class="tuner-controls">
          <button v-if="phase === 'idle'" type="button" class="tuner-action" @click="start">
            <span aria-hidden="true" class="tuner-action-dot"></span>{{ t('tuner.start') }}
          </button>
          <button v-else type="button" class="tuner-action tuner-stop" @click="stop">
            {{ t('tuner.stop') }}
          </button>
        </div>
        <p class="tuner-privacy">{{ t('tuner.privacy') }}</p>
      </section>
    </div>
  </Teleport>
</template>
