<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{ disabled: boolean; maxBytes: number }>();
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
  error.value = '';
  cancelled = false;
  chunks = [];
  seconds.value = 0;
  state.value = 'requesting';
  emit('active', true);
  // The learner may practise to a running metronome. Record only getUserMedia's
  // microphone stream; headphone monitoring minimizes acoustic click bleed.
  try {
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
    <strong>{{ t('attempt.micHeading') }}</strong>
    <p class="hint">{{ t('attempt.micPrivacy') }}</p>
    <p class="hint">{{ t('attempt.metronomePracticeTip') }}</p>
    <p v-if="!supported" class="hint">{{ t('attempt.micUnsupported') }}</p>
    <div v-else class="microphone-controls">
      <button v-if="state === 'idle'" type="button" class="recorder-button"
        :disabled="disabled" @click="start">{{ t('attempt.startRecording') }}</button>
      <span v-else-if="state === 'requesting'" class="hint" role="status">
        {{ t('attempt.requestingMic') }}
      </span>
      <template v-else>
        <span class="recording-timer" role="timer" :aria-label="t('attempt.recordingTimer')">
          {{ timeLabel }} / 02:00
        </span>
        <button type="button" class="recorder-button"
          :disabled="state !== 'recording'" @click="stop">{{ t('attempt.stopRecording') }}</button>
        <button type="button" class="recorder-button secondary-button"
          :disabled="state !== 'recording'" @click="cancel">{{ t('attempt.cancelRecording') }}</button>
      </template>
    </div>
    <div v-if="state === 'recording'" class="mic-level-panel">
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
