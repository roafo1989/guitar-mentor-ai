<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{ disabled: boolean; maxBytes: number }>();
const emit = defineEmits<{ recorded: [file: File] }>();
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

const state = ref<'idle' | 'recording' | 'stopping'>('idle');
const seconds = ref(0);
const error = ref('');
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

function release() {
  if (timer !== undefined) {
    clearInterval(timer);
    timer = undefined;
  }
  stream?.getTracks().forEach(track => track.stop());
  stream = null;
  recorder = null;
  chunks = [];
  state.value = 'idle';
}

function stop() {
  if (recorder?.state !== 'recording') return;
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
  try {
    const microphone = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    if (props.disabled || cancelled) {
      microphone.getTracks().forEach(track => track.stop());
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
    <p v-if="!supported" class="hint">{{ t('attempt.micUnsupported') }}</p>
    <div v-else class="microphone-controls">
      <button v-if="state === 'idle'" type="button" class="recorder-button"
        :disabled="disabled" @click="start">{{ t('attempt.startRecording') }}</button>
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
    <p v-if="error" role="alert" class="attempt-error">{{ error }}</p>
  </div>
</template>
