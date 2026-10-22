<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../audio/audio-session';
import { detectPitch, noteName } from './pitch';
import { TUNING_IDS, TUNINGS, readTuningPreference, saveTuningPreference, type TuningId } from './tuning-profiles';
import { autoStringIndex, newPitchTrack, resetPitchTrack, displayPitch, newTuneConfirmation, resetTuneConfirmation, confirmTunedPitch } from './tuner-session';
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
// Confirmation depends on multiple *fresh* candidate readings, never only
// on the current pitch and its rolling median.
const tunedStable = ref(false);
const tuningId = ref<TuningId>(readTuningPreference());
const tuning = computed(() => TUNINGS[tuningId.value]);
const strings = computed(() => tuning.value.strings);
const target = computed(() => selected.value === null ? null : strings.value[selected.value]);
// Frontal 3+3 headstock; the button circles sit well inside the
// 440-unit SVG, even at 320px viewport and minimum 45px mobile touch size.
// Reserve 5px additional CSS pixels for focus outline + outline-offset.
const pegs = [
  { x: 70, y: 211 }, { x: 70, y: 140 }, { x: 70, y: 69 },
  { x: 370, y: 69 }, { x: 370, y: 140 }, { x: 370, y: 211 },
] as const;
// From the nut to the matching *physical* winding post: E2/A2/D3
// (left bottom->top), G3/B3/E4 (right top->bottom). 
const stringPaths = [
  'M195 336 L195 301 L184 211',
  'M205 336 L205 301 L184 140',
  'M215 336 L215 301 L184 69',
  'M225 336 L225 301 L256 69',
  'M235 336 L235 301 L256 140',
  'M245 336 L245 301 L256 211',
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
            <svg viewBox="0 0 440 340" class="tuner-headstock-art" aria-hidden="true">
              <defs>
                <linearGradient id="gm-head-wood" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#48200d" />
                  <stop offset=".12" stop-color="#813c19" />
                  <stop offset=".33" stop-color="#ad6532" />
                  <stop offset=".52" stop-color="#95491e" />
                  <stop offset=".77" stop-color="#9c5429" />
                  <stop offset="1" stop-color="#371807" />
                </linearGradient>
                <linearGradient id="gm-head-metal" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#f4f6f4" />
                  <stop offset=".17" stop-color="#909da7" />
                  <stop offset=".38" stop-color="#e9f0f0" />
                  <stop offset=".57" stop-color="#697781" />
                  <stop offset=".79" stop-color="#28343e" />
                  <stop offset="1" stop-color="#b5bdc3" />
                </linearGradient>
                <linearGradient id="gm-head-metal-dark" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#e8edf0" />
                  <stop offset=".22" stop-color="#3c454c" />
                  <stop offset=".73" stop-color="#20272d" />
                  <stop offset="1" stop-color="#bcc8cf" />
                </linearGradient>
                <linearGradient id="gm-head-string" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#736e6a" />
                  <stop offset=".5" stop-color="#f3e5cc" />
                  <stop offset="1" stop-color="#756f65" />
                </linearGradient>
                <clipPath id="gm-head-outline">
                  <path d="M220 15 C234 15 240 27 253 31 Q264 34 271 47
                    Q283 51 286 67 L285 239 Q285 259 273 272
                    Q253 291 255 312 L255 340 H185 V312
                    Q187 291 167 272 Q155 259 155 239
                    L154 67 Q157 51 169 47 Q176 34 187 31
                    C200 27 206 15 220 15 Z" />
                </clipPath>
              </defs>
              <path d="M220 15 C234 15 240 27 253 31 Q264 34 271 47
                Q283 51 286 67 L285 239 Q285 259 273 272
                Q253 291 255 312 L255 340 H185 V312
                Q187 291 167 272 Q155 259 155 239
                L154 67 Q157 51 169 47 Q176 34 187 31
                C200 27 206 15 220 15 Z"
                fill="url(#gm-head-wood)" stroke="#f0e3d2" stroke-width="4"
                stroke-linejoin="round" />
              <g clip-path="url(#gm-head-outline)" class="tuner-woodgrain">
                <path d="M164 0 Q179 98 168 168 T183 343 M173 -8 Q191 122 177 257
                  M188 0 Q203 78 194 156 T201 335 M204 -8 Q215 89 209 178 T216 349
                  M224 0 Q232 78 225 160 T230 334 M242 0 Q231 95 248 180 T252 341
                  M259 0 Q254 80 267 173 T270 341 M276 0 Q261 110 282 230
                  M179 0 Q193 132 184 292 M232 0 Q222 152 237 316" />
              </g>
              <path d="M220 22 C235 21 241 36 258 38 Q270 46 276 64
                L275 237 Q275 256 263 267" fill="none"
                stroke="#f2bb82" stroke-opacity=".45" stroke-width="1.3" />
              <path d="M162 70 L163 240 Q165 257 175 268" fill="none"
                stroke="#2b1006" stroke-opacity=".5" stroke-width="2.5" />

              <!-- Compact, mirrored chrome 3+3 machines. Keys stay close
                   to the headstock silhouette, like the approved mockup. -->
              <g v-for="(post, index) in [69, 140, 211]" :key="'machine-' + index">
                <rect class="tuner-machine-shaft" x="151" :y="post-5" width="33" height="10" rx="4"
                  fill="url(#gm-head-metal-dark)" stroke="#71787b" stroke-width=".8" />
                <rect class="tuner-machine-shaft" x="256" :y="post-5" width="33" height="10" rx="4"
                  fill="url(#gm-head-metal-dark)" stroke="#71787b" stroke-width=".8" />
                <rect class="tuner-machine-key" x="128" :y="post-16" width="26" height="32" rx="10"
                  fill="url(#gm-head-metal)" stroke="#d8e2e5" stroke-width="1" />
                <rect class="tuner-machine-key" x="286" :y="post-16" width="26" height="32" rx="10"
                  fill="url(#gm-head-metal)" stroke="#d8e2e5" stroke-width="1" />
                <path :d="`M131 ${post-11} Q142 ${post-17} 151 ${post-10}`"
                  fill="none" stroke="#fff" stroke-opacity=".42" stroke-width="1.2" />
                <path :d="`M289 ${post-10} Q299 ${post-17} 309 ${post-11}`"
                  fill="none" stroke="#fff" stroke-opacity=".42" stroke-width="1.2" />
                <circle cx="184" :cy="post" r="13" fill="#3b3531"
                  stroke="url(#gm-head-metal)" stroke-width="5" />
                <circle cx="256" :cy="post" r="13" fill="#3b3531"
                  stroke="url(#gm-head-metal)" stroke-width="5" />
                <circle cx="184" :cy="post" r="4.5" fill="#babdb8" />
                <circle cx="256" :cy="post" r="4.5" fill="#babdb8" />
                <circle cx="184" :cy="post" r="1.5" fill="#393b3a" />
                <circle cx="256" :cy="post" r="1.5" fill="#393b3a" />
              </g>
              <!-- Truss-rod cover and two screws provide recognizable acoustic detail. -->
              <path d="M220 238 Q199 242 199 265 L204 314 Q220 319 236 314
                L241 265 Q241 242 220 238 Z" fill="#1a1818"
                stroke="#614a3c" stroke-width="1.5" />
              <circle cx="220" cy="252" r="3" fill="#c6c2b9" />
              <path d="M218.5 252 H221.5" stroke="#333" stroke-width="1" />
              <circle cx="220" cy="306" r="3" fill="#aaa39a" />
              <path d="M218.5 306 H221.5" stroke="#333" stroke-width="1" />
              <path v-for="(string, index) in strings" :key="'wire-' + string.number"
                :d="stringPaths[index]" class="tuner-wire"
                :class="{ active: selected === index, tuned: selected === index && isTuned }"
                :stroke-width="2.9 - index * .23" />
              <rect x="188" y="298" width="64" height="8" rx="2.2"
                fill="#e7dbca" stroke="#776953" stroke-width=".8" />
              <path d="M188 307 V340 M252 307 V340" stroke="#443126" stroke-width="1.5" />
            </svg>
            <button v-for="(string, index) in strings" :key="string.number" type="button"
              class="tuner-peg" :class="{ active: selected === index, tuned: selected === index && isTuned }"
              :style="{ left: (pegs[index].x / 440 * 100) + '%', top: (pegs[index].y / 340 * 100) + '%' }"
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
