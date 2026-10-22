<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import MicrophoneRecorder from './MicrophoneRecorder.vue';
import LearningResultsPanel from './LearningResultsPanel.vue';
import { useI18n } from 'vue-i18n';
import { AttemptHttpError, attemptService } from './attempt.service';
import type { SubmissionAttempt, UploadCapabilities } from './attempt.service';

const props = defineProps<{
  learnerId: string;
  sessionId: string;
  taskId: string;
}>();
const emit = defineEmits<{ 'draft-state': [state: { busy: boolean; dirty: boolean }] }>();
const { t } = useI18n();
const attempts = ref<SubmissionAttempt[]>([]);
const capabilities = ref<UploadCapabilities | null>(null);
const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const recordedPreview = ref<string | null>(null);

function clearRecordedPreview() {
  if (recordedPreview.value) URL.revokeObjectURL(recordedPreview.value);
  recordedPreview.value = null;
}

function onRecordingActive(active: boolean) {
  recordingActive.value = active;
  if (!active) return;
  // Avoid accidentally submitting a previously selected file while capturing.
  file.value = null;
  clearRecordedPreview();
  if (fileInput.value) fileInput.value.value = '';
  requestId.value = crypto.randomUUID();
  error.value = '';
  notice.value = '';
}

function onRecorded(recording: File) {
  clearRecordedPreview();
  if (fileInput.value) fileInput.value.value = '';
  file.value = recording;
  recordedPreview.value = URL.createObjectURL(recording);
  requestId.value = crypto.randomUUID();
  notice.value = '';
  error.value = '';
}
const comment = ref('');
const requestId = ref(crypto.randomUUID());
const loading = ref(true);
const uploading = ref(false);
const recordingActive = ref(false);
const decisionBusy = ref(false);
const learningTerminal = ref(false);
const error = ref('');
const notice = ref('');
const draftBusy = computed(() => uploading.value || recordingActive.value || !!file.value || !!comment.value.trim());
watch([uploading, recordingActive, decisionBusy, file, comment], () => {
  emit('draft-state', {
    busy: uploading.value || recordingActive.value || decisionBusy.value,
    dirty: !!file.value || !!comment.value.trim(),
  });
}, { immediate: true });
let polling: ReturnType<typeof setInterval> | undefined;
let historyRequestInFlight = false;

function statusLabel(attempt: SubmissionAttempt): string {
  const labels: Record<SubmissionAttempt['status'], string> = {
    UPLOADED: 'attempt.statusUploaded',
    PROCESSING: 'attempt.statusProcessing',
    PROCESSED: 'attempt.statusProcessed',
    FAILED: 'attempt.statusFailed',
  };
  return t(labels[attempt.status]);
}
function failureHint(attempt: SubmissionAttempt): string {
  const errors: Record<string, string> = {
    MEDIA_MISSING: 'attempt.failedMissing',
    SIZE_MISMATCH: 'attempt.failedSize',
    INVALID_FORMAT: 'attempt.failedFormat',
    INVALID_KEY: 'attempt.failedGeneric',
    IO_ERROR: 'attempt.failedGeneric',
    TECHNICAL_ERROR: 'attempt.failedGeneric',
    DURATION_EXCEEDED: 'attempt.failedDuration',
    INVALID_MEDIA: 'attempt.failedFormat',
    PROBE_UNAVAILABLE: 'attempt.failedProbe',
    PROBE_TIMEOUT: 'attempt.failedProbe',
    PROBE_INTERRUPTED: 'attempt.failedProbe',
    PROCESSING_INTERRUPTED: 'attempt.failedInterrupted',
    RETENTION_EXPIRED: 'attempt.failedExpired',
  };
  return t(errors[attempt.processingErrorCode || ''] || 'attempt.failedGeneric');
}

async function pollHistory() {
  if (historyRequestInFlight || uploading.value || loading.value ||
      !attempts.value.some(item => item.status === 'UPLOADED' || item.status === 'PROCESSING')) return;
  historyRequestInFlight = true;
  try {
    attempts.value = await attemptService.list(props.learnerId, props.sessionId, props.taskId);
  } catch {
    // Keep the last known history. Manual refresh can surface network errors.
  } finally {
    historyRequestInFlight = false;
  }
}

function uploadErrorMessage(cause: unknown): string {
  if (!(cause instanceof AttemptHttpError)) return t('attempt.uploadNetworkError');
  const known: Record<string, string> = {
    INVALID_AUDIO_CONTENT: 'attempt.invalidAudioContent',
    UNSUPPORTED_AUDIO_FORMAT: 'attempt.formatError',
    INVALID_AUDIO_SIZE: 'attempt.sizeError',
    MISSING_AUDIO_FILENAME: 'attempt.formatError',
    COMMENT_TOO_LONG: 'attempt.commentTooLong',
    UPLOAD_DISABLED: 'attempt.uploadDisabled',
  };
  if (known[cause.code]) return t(known[cause.code]);
  if (cause.status === 400 || cause.status === 413) return t('attempt.invalidUpload');
  return t('attempt.uploadServerError');
}

function selectFile(event: Event) {
  clearRecordedPreview();
  const input = event.target as HTMLInputElement;
  file.value = input.files?.[0] ?? null;
  requestId.value = crypto.randomUUID();
  error.value = '';
  notice.value = '';
}
async function refresh() {
  error.value = '';
  try {
    const [rules, history] = await Promise.all([
      attemptService.capabilities(props.learnerId, props.sessionId, props.taskId),
      attemptService.list(props.learnerId, props.sessionId, props.taskId),
    ]);
    capabilities.value = rules;
    attempts.value = history;
  } catch {
    error.value = t('attempt.historyLoadError');
  } finally {
    loading.value = false;
  }
}
async function submit() {
  const chosen = file.value;
  if (!chosen || learningTerminal.value || !capabilities.value?.enabled || uploading.value || recordingActive.value) return;
  if (chosen.size === 0 || chosen.size > capabilities.value.maxBytes) {
    error.value = t('attempt.sizeError');
    return;
  }
  if (!/\.(mp3|wav|ogg|webm|m4a)$/i.test(chosen.name)) {
    error.value = t('attempt.formatError');
    return;
  }
  uploading.value = true;
  error.value = '';
  notice.value = '';
  try {
    await attemptService.upload(props.learnerId, props.sessionId, props.taskId,
      chosen, comment.value, requestId.value);
    file.value = null;
    clearRecordedPreview();
    // File inputs are native browser controls: clearing Vue state alone does not reset their label.
    if (fileInput.value) fileInput.value.value = '';
    comment.value = '';
    requestId.value = crypto.randomUUID();
    notice.value = t('attempt.uploaded');
    await refresh();
  } catch (cause) {
    error.value = uploadErrorMessage(cause);
    // Retain the selected file and idempotency key; user can retry or pick another file.
  } finally {
    uploading.value = false;
  }
}
function fileSize(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}
onMounted(() => {
  void refresh();
  polling = setInterval(() => { void pollHistory(); }, 3000);
});
onBeforeUnmount(() => {
  if (polling !== undefined) clearInterval(polling);
  clearRecordedPreview();
});
</script>

<template>
  <section class="homework-panel" aria-labelledby="homework-heading">
    <div class="homework-heading">
      <h3 id="homework-heading">{{ t('attempt.title') }}</h3>
      <p class="hint">{{ t('attempt.subtitle') }}</p>
    </div>
    <div v-if="loading" class="hint">{{ t('attempt.loading') }}</div>
    <template v-else>
      <p v-if="!capabilities?.enabled" class="hint">
        {{ t('attempt.disabledHelp') }}
      </p>
      <p v-if="learningTerminal" class="hint" role="status">{{ t('learning.uploadClosed') }}</p>
      <form class="homework-form" @submit.prevent="submit">
        <label for="attempt-file">{{ t('attempt.fileLabel') }}</label>
        <input id="attempt-file" ref="fileInput" type="file" accept=".mp3,.wav,.ogg,.webm,.m4a,audio/*"
          :disabled="learningTerminal || !capabilities?.enabled || uploading || recordingActive" @change="selectFile" />
        <p class="hint">{{ t('attempt.fileLimits') }}</p>
        <MicrophoneRecorder :disabled="learningTerminal || !capabilities?.enabled || uploading"
          :max-bytes="capabilities?.maxBytes ?? 20971520"
          @recorded="onRecorded" @active="onRecordingActive" />
        <div v-if="recordedPreview && file" class="recorded-preview">
          <strong>{{ t('attempt.recordingReady') }} ({{ fileSize(file.size) }})</strong>
          <audio :src="recordedPreview" controls preload="metadata"
            :aria-label="t('attempt.listenRecording')" />
          <button type="button" class="recorder-button secondary-button"
            :disabled="uploading" @click="file = null; clearRecordedPreview()">
            {{ t('attempt.discardRecording') }}
          </button>
        </div>
        <label for="attempt-comment">{{ t('attempt.commentLabel') }}</label>
        <textarea id="attempt-comment" v-model="comment" rows="3" maxlength="2000"
          :disabled="!capabilities?.enabled || uploading"
          :placeholder="t('attempt.commentPlaceholder')" />
        <div class="form-actions">
          <button type="submit" class="action-button"
            :disabled="learningTerminal || !file || !capabilities?.enabled || uploading || recordingActive">
            {{ uploading ? t('attempt.uploading') : t('attempt.submit') }}
          </button>
        </div>
      </form>
      <p v-if="notice" role="status" class="saved-status">{{ notice }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <LearningResultsPanel :learner-id="learnerId" :session-id="sessionId" :task-id="taskId"
        :attempts="attempts" :draft-busy="draftBusy" @decision-busy="decisionBusy = $event" @terminal-state="learningTerminal = $event" />
      <div class="homework-history">
        <h4>{{ t('attempt.history') }}</h4>
        <p v-if="!attempts.length" class="hint">{{ t('attempt.noAttempts') }}</p>
        <article v-for="item in attempts" :key="item.id" class="attempt-card">
          <div class="attempt-card-title">
            <strong>{{ t('attempt.attempt') }} №{{ item.number }}</strong>
            <span class="hint">{{ new Date(item.submittedAt).toLocaleString() }}</span>
          </div>
          <p class="hint">{{ item.format.toUpperCase() }} · {{ fileSize(item.bytes) }}</p>
          <p v-if="item.comment">{{ item.comment }}</p>
          <p class="attempt-unreviewed" role="status">
            {{ statusLabel(item) }}
            <span v-if="item.status === 'FAILED'"> · {{ failureHint(item) }}</span>
          </p>
          <p v-if="item.mediaState === 'DELETED'" class="hint">{{ t('attempt.mediaDeleted') }}</p>
          <p v-else-if="item.mediaState === 'DELETE_PENDING'" class="hint">{{ t('attempt.mediaPending') }}</p>
        </article>
      </div>
    </template>
  </section>
</template>
