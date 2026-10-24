<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { acquireAudio, releaseAudio } from '../../shared/audio/audio-session';

const props = defineProps<{ disabled: boolean; maxBytes: number; compact?: boolean }>();
const emit = defineEmits<{ recorded: [file: File]; active: [value: boolean] }>();
const { t } = useI18n();

const MAX_DURATION_SECONDS = 120;
const preferences = [
  { mime: 'audio/webm;codecs=opus', extension: 'webm' },
  { mime: 'audio/webm', extension: 'webm' },
  { mime: 'audio/ogg;codecs=opus', extension: 'ogg' },
  { mime: 'audio/ogg', extension: 'ogg' },
];
const supported = typeof navigator !== 'undefined' &&
  !!navigator.mediaDevices?.getUserMedia &&
  typeof MediaRecorder !== 'undefined' &&
  preferences.some(choice => MediaRecorder.isTypeSupported(choice.mime));

const state = ref<'idle' | 'requesting' | 'recording' | 'stopping'>('idle');
const seconds = ref(0);
const error = ref('');
// Input signal strength for recording UX only; never a musical-skill assessment.
const meterLevel = ref(0);
const meterAvailable = ref(false);
const meterSegments = 24;
const signalPresent = computed(() => meterLevel.value >= 12);
const timeLabel = computed(() => {
  const minutes = Math.floor(seconds.value / 60);
  return String(minutes).padStart(2, '0') + ':' + String(seconds.value % 60).padStart(2, '0');
});

const audioToken = Symbol('recording');
let stream: MediaStream | null = null;
let recorder: MediaRecorder | null = null;
let chunks: Blob[] = [];
let cancelled = false;
let timer: ReturnType<typeof setInterval> | undefined;
let startedAt = 0;
let extension = 'webm';
let audioContext: AudioContext | null = null;
let audioSource: MediaStreamAudioSourceNode | null = null;
let analyser: AnalyserNode | null = null;
let meterFrame: number | null = null;

function stopMeter() {
  if (meterFrame !== null) {
    cancelAnimationFrame(meterFrame);
    meterFrame = null;
  }
  audioSource?.disconnect();
  analyser?.disconnect();
  audioSource = null;
  analyser = null;
  // Closing releases the sound hardware graph (microphone tracks are stopped separately).
  const previous = audioContext;
  audioContext = null;
  if (previous) void previous.close().catch(() => undefined);
  meterLevel.value = 0;
  meterAvailable.value = false;
}

function startMeter(microphone: MediaStream) {
  stopMeter();
  if (typeof AudioContext === 'undefined') return;
  try {
    audioContext = new AudioContext();
    audioSource = audioContext.createMediaStreamSource(microphone);
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 2048;
    analyser.smoothingTimeConstant = 0.75;
    audioSource.connect(analyser);
    // Never connect to audioContext.destination: avoid microphone feedback/echo.
    meterAvailable.value = true;
    if (audioContext.state === 'suspended') {
      void audioContext.resume().catch(() => { meterAvailable.value = false; });
    }
    const samples = new Uint8Array(analyser.fftSize);
    let previousFrame = 0;
    const update = (now: number) => {
      if (now - previousFrame >= 80 && analyser) {
        previousFrame = now;
        analyser.getByteTimeDomainData(samples);
        let sum = 0;
        for (let i = 0; i < samples.length; i++) {
          const sample = (samples[i] - 128) / 128;
          sum += sample * sample;
        }
        const rms = Math.sqrt(sum / samples.length);
        const decibels = 20 * Math.log10(Math.max(rms, 0.00001));
        // Display-only scale: -55 dBFS means essentially silent; -10 dBFS fills the bar.
        meterLevel.value = Math.max(0, Math.min(100, Math.round((decibels + 55) * 100 / 45)));
      }
      meterFrame = requestAnimationFrame(update);
    };
    meterFrame = requestAnimationFrame(update);
  } catch {
    stopMeter(); // Failure of the meter must not break audio recording.
  }
}

function release() {
  stopMeter();
  if (timer !== undefined) {
    clearInterval(timer);
    timer = undefined;
  }
  stream?.getTracks().forEach(track => track.stop());
  stream = null;
  releaseAudio(audioToken);
  recorder = null;
  chunks = [];
  state.value = 'idle';
  emit('active', false);
}

function stop() {
  if (recorder?.state !== 'recording') return;
  stopMeter();
  state.value = 'stopping';
  if (timer !== undefined) {
    clearInterval(timer);
    timer = undefined;
  }
  recorder.stop();
}

function cancel() {
  cancelled = true;
  if (recorder?.state === 'recording') {
    stop();
  } else {
    release();
  }
  seconds.value = 0;
  error.value = '';
}

async function start() {
  if (props.disabled || state.value !== 'idle' || !supported) return;
  const choice = preferences.find(item => MediaRecorder.isTypeSupported(item.mime));
  if (!choice) return;
  if (!acquireAudio('recorder', audioToken, cancel)) {
    error.value = t('attempt.microphoneBusy');
    return;
  }
  error.value = '';
  cancelled = false;
  chunks = [];
  seconds.value = 0;
  state.value = 'requesting';
  emit('active', true);
  try {
    // The learner may practise to a running metronome. Record only getUserMedia's
    // microphone stream; headphone monitoring minimizes acoustic click bleed.
    const microphone = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    if (props.disabled || cancelled) {
      microphone.getTracks().forEach(track => track.stop());
      release();
      return;
    }
    stream = microphone;
    extension = choice.extension;
    const active = new MediaRecorder(microphone, { mimeType: choice.mime });
    recorder = active;
    active.ondataavailable = event => {
      if (event.data.size > 0) chunks.push(event.data);
    };
    active.onerror = () => {
      cancelled = true;
      error.value = t('attempt.recorderError');
      if (active.state === 'recording') active.stop();
      else release();
    };
    active.onstop = () => {
      // A stop creates a local draft, never an automatic submission.
      const data = cancelled ? null : new Blob(chunks, { type: active.mimeType });
      release();
      if (cancelled || !data) return;
      if (data.size === 0) {
        error.value = t('attempt.recorderEmpty');
      } else if (data.size > props.maxBytes) {
        error.value = t('attempt.sizeError');
      } else {
        emit('recorded', new File([data],
          'guitar-practice-' + Date.now() + '.' + extension,
          { type: active.mimeType }));
      }
    };
    active.start(1000);
    state.value = 'recording';
    startMeter(microphone);
    startedAt = Date.now();
    timer = setInterval(() => {
      seconds.value = Math.floor((Date.now() - startedAt) / 1000);
      if (seconds.value >= MAX_DURATION_SECONDS) stop();
    }, 250);
  } catch {
    release();
    error.value = t('attempt.microphoneDenied');
  }
}

onBeforeUnmount(() => {
  cancelled = true;
  if (recorder?.state === 'recording') recorder.stop();
  release();
});
</script>

<template>
  <div class="microphone-recorder">
    <strong v-if="!compact">{{ t('attempt.micHeading') }}</strong>
    <template v-if="!compact">
      <p class="hint">{{ t('attempt.micPrivacy') }}</p>
      <p class="hint">{{ t('attempt.metronomePracticeTip') }}</p>
    </template>
    <details v-else class="compact-recording-info">
      <summary>{{ t('attempt.microphoneInstructions') }}</summary>
      <p class="hint">{{ t('attempt.micPrivacy') }}</p>
      <p class="hint">{{ t('attempt.metronomePracticeTip') }}</p>
    </details>
    <p v-if="!supported" class="hint">{{ t('attempt.micUnsupported') }}</p>
    <div v-if="compact && supported" class="studio-live-wave" role="meter"
      :aria-label="t('attempt.micLevelLabel')" aria-valuemin="0" aria-valuemax="100"
      :aria-valuenow="state === 'recording' && meterAvailable ? meterLevel : 0">
      <span v-for="tick in 42" :key="tick" class="studio-wave-bar"
        :class="{ 'is-listening': state === 'recording' && meterAvailable && meterLevel >= (tick % 9 + 1) * 10 }"
        :style="{ height: (18 + ((tick * 17 + 13) % 68)) + '%' }" />
      <span class="studio-wave-timer">{{ state === 'recording' ? timeLabel : '00:00' }} / 02:00</span>
    </div>
    <div v-if="supported" class="microphone-controls">
      <button v-if="state === 'idle'" type="button" class="recorder-button"
        :disabled="disabled" :aria-label="compact ? t('attempt.startRecording') : undefined" @click="start"><span v-if="compact" class="studio-record-dot" aria-hidden="true"></span><span v-else>{{ t('attempt.startRecording') }}</span></button>
      <span v-else-if="state === 'requesting'" class="hint" role="status">
        {{ t('attempt.requestingMic') }}
      </span>
      <template v-else>
        <span class="recording-timer" role="timer" :aria-label="t('attempt.recordingTimer')">
          {{ timeLabel }} / 02:00
        </span>
        <button type="button" class="recorder-button"
          :disabled="state !== 'recording'" :aria-label="compact ? t('attempt.stopRecording') : undefined" @click="stop"><span v-if="compact" class="studio-stop-square" aria-hidden="true"></span><span v-else>{{ t('attempt.stopRecording') }}</span></button>
        <button type="button" class="recorder-button secondary-button" :class="{ 'studio-cancel-button': compact }"
          :disabled="state !== 'recording'" @click="cancel">{{ t('attempt.cancelRecording') }}</button>
      </template>
      <slot name="actions" />
    </div>
    <div v-if="state === 'recording' && !compact" class="mic-level-panel">
      <div class="mic-level-heading">
        <span>{{ t('attempt.micLevelLabel') }}</span>
        <span v-if="meterAvailable" class="mic-level-status">
          {{ signalPresent ? t('attempt.micSignalDetected') : t('attempt.micSignalLow') }}
        </span>
      </div>
      <template v-if="meterAvailable">
        <div class="mic-level-track" role="meter"
          :aria-label="t('attempt.micLevelLabel')" aria-valuemin="0" aria-valuemax="100"
          :aria-valuenow="meterLevel">
          <span v-for="tick in meterSegments" :key="tick" class="mic-level-segment"
            :class="{ 'is-active': meterLevel >= tick * 100 / meterSegments }" />
        </div>
        <p class="hint">{{ t('attempt.micLevelHint') }}</p>
      </template>
      <p v-else class="hint">{{ t('attempt.micLevelUnavailable') }}</p>
    </div>
    <p v-if="error" role="alert" class="attempt-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.compact-recording-info {margin:0 0 12px;font-size:12px;color:#64746f}
.compact-recording-info summary {cursor:pointer;min-height:38px;display:flex;align-items:center;color:#45685e}
.compact-recording-info summary:focus-visible {outline:3px solid #28776d;outline-offset:2px}
 
.studio-live-wave {height:62px;border-radius:12px;background:#f4f8f6;padding:10px 82px 10px 12px;display:flex;align-items:center;gap:3px;position:relative;overflow:hidden}
.studio-wave-bar {flex:1;min-width:2px;max-width:6px;background:#d2ded8;border-radius:4px;transition:background-color .1s}
.studio-wave-bar.is-listening {background:#438e68}
.studio-wave-timer {position:absolute;right:10px;top:50%;transform:translateY(-50%);font-variant-numeric:tabular-nums;font-size:11px;color:#51605b;white-space:nowrap}
.microphone-recorder:has(.studio-live-wave) .microphone-controls {display:flex;gap:8px;align-items:center;flex-wrap:nowrap;margin:10px 0;min-width:0}
.microphone-recorder:has(.studio-live-wave) .microphone-controls > button:first-child {flex:0 0 50px;width:50px;height:50px;min-width:50px;min-height:50px;padding:0;border:5px solid #e5e9e7;background:white;border-radius:999px;display:grid;place-items:center;box-shadow:0 0 0 1px #cfd8d3}
.studio-record-dot {width:26px;height:26px;border-radius:999px;background:#dd3d37;display:block}
.studio-stop-square {width:20px;height:20px;border-radius:5px;background:#dd3d37;display:block}
.microphone-recorder:has(.studio-live-wave) .microphone-controls .studio-cancel-button {flex:0 1 74px;font-size:11px;padding:6px}
@media(max-width:420px) {.microphone-recorder:has(.studio-live-wave) .microphone-controls {flex-wrap:wrap}}

.microphone-recorder:has(.studio-live-wave) .recording-timer {display:none}
.microphone-recorder:has(.studio-live-wave) .recorder-button {border-radius:999px;min-height:44px}
</style>
