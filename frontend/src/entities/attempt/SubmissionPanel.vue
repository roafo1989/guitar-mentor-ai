<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import MicrophoneRecorder from './MicrophoneRecorder.vue';
import LearningResultsPanel from './LearningResultsPanel.vue';
import { useI18n } from 'vue-i18n';
import { AttemptHttpError, attemptService } from './attempt.service';
import type { AttemptPage, SubmissionAttempt, UploadCapabilities } from './attempt.service';

const props = defineProps<{
  learnerId: string;
  sessionId: string;
  taskId: string;
}>();
const emit = defineEmits<{ 'draft-state': [state: { busy: boolean; dirty: boolean }] }>();
const { t } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const attempts = ref<SubmissionAttempt[]>([]);
const pinned = ref<SubmissionAttempt[]>([]);
const selectedId = ref('');
const latestId = ref('');
const pageCursor = ref<number | undefined>(undefined);
const cursorStack = ref<(number | undefined)[]>([]);
const hasMore = ref(false);
const historyStale = ref(false);
const historyNotice = ref('');
const historyError = ref('');
const pageBusy = ref(false);
const displayAttempts = computed(() => {
  const unique = new Map<string, SubmissionAttempt>();
  for (const item of [...attempts.value, ...pinned.value]) unique.set(item.id, item);
  return [...unique.values()].sort((a, b) => b.number - a.number);
});
const isProcessing = (a: SubmissionAttempt) => a.status === 'UPLOADED' || a.status === 'PROCESSING';
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
let requestSerial = 0;
let pinSerial = 0;
// Each context owns its own in-flight set. An old request may settle after
// navigation, but its cleanup must never touch the new context's bookkeeping.
let activeDetails = new Set<Promise<SubmissionAttempt>>();
const detailCache = new Map<string, Promise<SubmissionAttempt>>();
function fetchDetail(id: string): Promise<SubmissionAttempt> {
  const existing = detailCache.get(id);
  if (existing) return existing;
  const { learnerId, sessionId, taskId } = props;
  const inFlight = activeDetails;
  let request: Promise<SubmissionAttempt>;
  request = attemptService.detail(learnerId, sessionId, taskId, id)
    .finally(() => {
      // Deduplicate only in-flight GETs, never cache fulfilled or rejected
      // results. A manual results refresh must always revalidate the detail.
      if (detailCache.get(id) === request) detailCache.delete(id);
      inFlight.delete(request);
    });
  inFlight.add(request);
  detailCache.set(id, request);
  return request;
}
let pendingLatest = false;
let pendingRefresh = false;
let contextGeneration = 0;
let mounted = true;
function currentContext(generation: number): boolean {
  return mounted && generation === contextGeneration;
}
function resetForContext(): void {
  ++contextGeneration;
  ++requestSerial;
  ++pinSerial;
  detailCache.clear();
  activeDetails = new Set<Promise<SubmissionAttempt>>();
  historyRequestInFlight = false;
  pendingLatest = false;
  pendingRefresh = false;
  pageBusy.value = false;
  attempts.value = [];
  pinned.value = [];
  selectedId.value = '';
  latestId.value = '';
  pageCursor.value = undefined;
  cursorStack.value = [];
  hasMore.value = false;
  historyError.value = '';
  historyStale.value = false;
  historyNotice.value = '';
  capabilities.value = null;
  loading.value = true;
  error.value = '';
  notice.value = '';
  learningTerminal.value = false;
  // Context switching is blocked during active capture/upload by the route guard.
  file.value = null;
  if (fileInput.value) fileInput.value.value = '';
  clearRecordedPreview();
  comment.value = '';
  requestId.value = crypto.randomUUID();
  void refresh();
}
watch(() => [props.learnerId, props.sessionId, props.taskId], resetForContext);

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

async function readPage(cursor: number | null | undefined = pageCursor.value, keepBusy = false): Promise<boolean> {
  if (historyRequestInFlight) return false;
  historyRequestInFlight = true;
  const serial = ++requestSerial, generation = contextGeneration;
  // Keep in-flight requests discoverable throughout page reloads. Clearing
  // the map here would allow duplicate GETs for the same selected UUID.
  const { learnerId, sessionId, taskId } = props;
  // Polling is also busy: never display actionable navigation that silently no-ops.
  pageBusy.value = true;
  if (!keepBusy) historyNotice.value = '';
  try {
    const page: AttemptPage = await attemptService.page(learnerId, sessionId, taskId, cursor ?? undefined);
    if (!currentContext(generation) || serial !== requestSerial) return false;
    // Preserve the previous page when reading a requested destination fails.
    attempts.value = page.items;
    hasMore.value = page.hasMore;
    historyError.value = '';
    historyStale.value = false;
    if (!keepBusy) historyNotice.value = t('attempt.historyUpdated');
    // The page and pinned details form one bounded poll cycle.
    await refreshPinned(generation, serial);
    return true;
  } catch {
    if (currentContext(generation) && serial === requestSerial) {
      historyError.value = t('attempt.historyLoadError');
      historyStale.value = true;
    }
    return false;
  } finally {
    if (currentContext(generation) && serial === requestSerial) {
      historyRequestInFlight = false;
      pageBusy.value = false;
      if (pendingLatest) {
        pendingLatest = false; pendingRefresh = false;
        void navigate(undefined, 'latest');
      } else if (pendingRefresh) {
        pendingRefresh = false;
        void readPage();
      }
    }
  }
}
async function refreshPinned(generation: number, serial: number) {
  const pin = ++pinSerial;
  const ids = [...new Set([selectedId.value, latestId.value].filter(Boolean))]
    .filter(id => !attempts.value.some(a => a.id === id)).slice(0, 2);
  const results = await Promise.allSettled(ids.map(id => fetchDetail(id)));
  if (!currentContext(generation) || serial !== requestSerial || pin !== pinSerial) return;
  // Keep the last displayed DTO when a detail GET fails; freshness is guarded by the child.
  const previous = new Map(pinned.value.map(item => [item.id, item]));
  pinned.value = ids.flatMap((id, index) => {
    const result = results[index];
    return result.status === 'fulfilled' ? [result.value] : previous.has(id) ? [previous.get(id)!] : [];
  });
}
async function navigate(cursor: number | undefined, direction: 'older' | 'newer' | 'latest') {
  if (historyRequestInFlight) {
    if (direction === 'latest') pendingLatest = true;
    return;
  }
  if (pageBusy.value) return;
  const old = pageCursor.value;
  const stack = [...cursorStack.value];
  if (direction === 'older') stack.push(old);
  if (direction === 'newer') stack.pop();
  if (await readPage(direction === 'latest' ? null : cursor)) {
    pageCursor.value = direction === 'latest' ? undefined : cursor;
    cursorStack.value = direction === 'latest' ? [] : stack;
  }
}
function requestRefresh() {
  if (historyRequestInFlight) { pendingRefresh = true; return; }
  void readPage();
}
watch([selectedId, latestId], () => {
  if (!loading.value && !historyRequestInFlight) {
    const seq = requestSerial;
    void refreshPinned(contextGeneration, seq);
  }
});
async function pollHistory() {
  if ((typeof document !== 'undefined' && document.hidden) || uploading.value || loading.value || pageBusy.value || historyRequestInFlight || activeDetails.size > 0 ||
      !displayAttempts.value.some(isProcessing)) return;
  await readPage(pageCursor.value, true);
}
function onVisibility() {
  if ((typeof document === 'undefined' || !document.hidden) && !loading.value) requestRefresh();
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
  const generation = contextGeneration;
  const { learnerId, sessionId, taskId } = props;
  if (currentContext(generation)) error.value = '';
  try {
    const rules = await attemptService.capabilities(learnerId, sessionId, taskId);
    if (currentContext(generation)) capabilities.value = rules;
  } catch {
    if (currentContext(generation)) error.value = t('attempt.historyLoadError');
  } finally {
    if (currentContext(generation)) {
      await readPage();
      loading.value = false;
    }
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
  const generation = contextGeneration;
  const { learnerId, sessionId, taskId } = props;
  uploading.value = true;
  error.value = '';
  notice.value = '';
  try {
    await attemptService.upload(learnerId, sessionId, taskId,
      chosen, comment.value, requestId.value);
    if (!currentContext(generation)) return;
    file.value = null;
    clearRecordedPreview();
    // File inputs are native browser controls: clearing Vue state alone does not reset their label.
    if (fileInput.value) fileInput.value.value = '';
    comment.value = '';
    requestId.value = crypto.randomUUID();
    notice.value = t('attempt.uploaded');
    await navigate(undefined, 'latest');
  } catch (cause) {
    if (currentContext(generation)) error.value = uploadErrorMessage(cause);
    // Retain the selected file and idempotency key; user can retry or pick another file.
  } finally {
    uploading.value = false;
  }
}
function fileSize(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}
onMounted(() => {
  mounted = true;
  void refresh();
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility);
  polling = setInterval(() => { void pollHistory(); }, 3000);
});
onBeforeUnmount(() => {
  mounted = false;
  ++contextGeneration;
  ++requestSerial;
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility);
  if (polling !== undefined) clearInterval(polling);
  clearRecordedPreview();
});
</script>

<template>
  <section class="homework-panel" aria-labelledby="homework-heading">
    <div class="homework-heading">
      <h3 id="homework-heading"><span v-if="lightMinimal" aria-hidden="true">♩ </span>{{ t('attempt.title') }}</h3>
      <p v-if="!lightMinimal" class="hint">{{ t('attempt.subtitle') }}</p>
    </div>
    <div v-if="loading" class="hint">{{ t('attempt.loading') }}</div>
    <template v-else>
      <p v-if="!capabilities?.enabled" class="hint" role="status">
        {{ t('attempt.disabledHelp') }}
      </p>
      <p v-if="!capabilities?.enabled && lightMinimal" class="hint">
        {{ t('attempt.localRecordingAvailable') }}
      </p>
      <p v-if="learningTerminal" class="hint" role="status">{{ t('learning.uploadClosed') }}</p>
      <form class="homework-form" @submit.prevent="submit">
        <div v-if="lightMinimal" class="recording-primary">
          <h4>{{ t('attempt.microphoneTitle') }}</h4>
          <MicrophoneRecorder :compact="lightMinimal" :disabled="learningTerminal || uploading"
          :max-bytes="capabilities?.maxBytes ?? 20971520"
          @recorded="onRecorded" @active="onRecordingActive" />
        </div>
        <details v-if="lightMinimal" class="recording-file-option">
          <summary>{{ t('attempt.uploadAlternative') }}</summary>
          <label for="attempt-file">{{ t('attempt.fileLabel') }}</label>
        <input id="attempt-file" ref="fileInput" type="file" accept=".mp3,.wav,.ogg,.webm,.m4a,audio/*"
          :disabled="learningTerminal || !capabilities?.enabled || uploading || recordingActive" @change="selectFile" />
        <p class="hint">{{ t('attempt.fileLimits') }}</p>
        </details>
        <template v-if="!lightMinimal">
          <label for="attempt-file">{{ t('attempt.fileLabel') }}</label>
        <input id="attempt-file" ref="fileInput" type="file" accept=".mp3,.wav,.ogg,.webm,.m4a,audio/*"
          :disabled="learningTerminal || !capabilities?.enabled || uploading || recordingActive" @change="selectFile" />
        <p class="hint">{{ t('attempt.fileLimits') }}</p>
          <MicrophoneRecorder :compact="lightMinimal" :disabled="learningTerminal || uploading"
          :max-bytes="capabilities?.maxBytes ?? 20971520"
          @recorded="onRecorded" @active="onRecordingActive" />
        </template>
        <div v-if="recordedPreview && file" class="recorded-preview">
          <strong>{{ t('attempt.recordingReady') }} ({{ fileSize(file.size) }})</strong>
          <audio :src="recordedPreview" controls preload="metadata"
            :aria-label="t('attempt.listenRecording')" />
          <button type="button" class="recorder-button secondary-button"
            :disabled="uploading" @click="file = null; clearRecordedPreview()">
            {{ t('attempt.discardRecording') }}
          </button>
        </div>
        <label for="attempt-comment" :class="{ 'studio-secondary-label': lightMinimal }">{{ t('attempt.commentLabel') }}</label>
        <textarea id="attempt-comment" v-model="comment" rows="3" maxlength="2000"
          :disabled="uploading"
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
      <div v-if="lightMinimal" class="studio-results-divider"></div>
      <LearningResultsPanel :learner-id="learnerId" :session-id="sessionId" :task-id="taskId"
         :attempts="displayAttempts" :selected-id="selectedId" :latest-id="latestId"
        :draft-busy="draftBusy" :load-attempt-detail="fetchDetail" @selected-id="selectedId = $event" @latest-id="latestId = $event" @decision-busy="decisionBusy = $event" @terminal-state="learningTerminal = $event" />
      <details class="homework-history">
        <summary>{{ t('attempt.historyDetails') }}</summary>
        <p v-if="historyError" role="alert" class="error">{{ historyError }} <button type="button" :disabled="pageBusy" @click="requestRefresh()">{{ t('learning.retry') }}</button></p>
        <p v-if="historyStale" role="status" class="hint">{{ t('attempt.historyStale') }}</p>
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
        <p v-if="!hasMore && !cursorStack.length && !pageBusy" class="hint">{{ t('attempt.onlyPage') }}</p>
        <nav class="form-actions" :aria-label="t('attempt.history')">
          <button type="button" class="secondary-button" :disabled="pageBusy || !cursorStack.length" @click="navigate(cursorStack[cursorStack.length - 1], 'newer')">{{ t('attempt.newer') }}</button>
          <button type="button" class="secondary-button" :disabled="pageBusy || !hasMore" @click="navigate(attempts[attempts.length - 1]?.number, 'older')">{{ t('attempt.older') }}</button>
          <button type="button" class="secondary-button" :disabled="pageBusy" @click="navigate(undefined, 'latest')">{{ t('attempt.latest') }}</button>
          <button type="button" class="secondary-button" :disabled="pageBusy" @click="readPage()">{{ t('attempt.refresh') }}</button>
        </nav>
        <p v-if="historyNotice && !historyError" role="status" class="hint">{{ historyNotice }}</p>
      </details>
    </template>
  </section>
</template>

<style scoped>
.recording-primary {border:1px solid #dae7e1;background:#f6faf8;border-radius:12px;padding:14px;min-width:0}
.recording-primary h4 {font-size:14px;font-weight:650;margin:0 0 10px}
.recording-file-option {border-bottom:1px solid #dce5e4;padding:12px 0}
.recording-file-option summary {min-height:44px;display:flex;align-items:center;color:#205f57;font-weight:600;cursor:pointer}
.recording-file-option summary:focus-visible {outline:3px solid #28776d;outline-offset:2px}
.recording-file-option input[type=file] {display:block;margin:10px 0;max-width:100%}
.recording-primary :deep(.microphone-recorder) {min-width:0;max-width:100%}

.homework-panel:has(.recording-primary) .homework-heading {display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #eef1ef;padding-bottom:10px;margin-bottom:12px}
.homework-panel:has(.recording-primary) .homework-heading h3 {font-size:16px!important;margin:0;font-weight:700}
.homework-panel:has(.recording-primary) .homework-form {display:flex;flex-direction:column;gap:12px}
.homework-panel:has(.recording-primary) .recording-primary {padding:0;background:transparent;border:0;margin:0}
.homework-panel:has(.recording-primary) .recording-primary h4 {display:none}
.homework-panel:has(.recording-primary) .recorded-preview {border:1px solid #dbe8df;border-radius:12px;background:#f4f9f6;padding:12px;display:grid;gap:10px}
.homework-panel:has(.recording-primary) .recorded-preview audio {width:100%;max-width:100%}
.homework-panel:has(.recording-primary) .form-actions {display:flex;margin:0;gap:8px}
.homework-panel:has(.recording-primary) .form-actions button {flex:1;min-height:42px;border-radius:10px}
.homework-panel:has(.recording-primary) .studio-secondary-label {font-size:12px;color:#52645d;margin:2px 0 0}
.homework-panel:has(.recording-primary) textarea {border:1px solid #dbe5df;border-radius:10px;min-height:68px}
.homework-panel:has(.recording-primary) .recording-file-option {margin:0;padding:2px 0;border-bottom:1px solid #eef2ef}
.homework-panel:has(.recording-primary) .studio-results-divider {border-top:1px solid #edf1ed;margin:18px 0 12px}
</style>
