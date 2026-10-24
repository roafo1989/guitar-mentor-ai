<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import MicrophoneRecorder from './MicrophoneRecorder.vue';
import LearningResultsPanel from './LearningResultsPanel.vue';
import { openExerciseMetronome, metronomeUi } from '../metronome/metronome-ui';
import { metronome } from '../../shared/audio/metronome';
import type { Exercise } from '../../shared/model/diagnostic.model';
import { useI18n } from 'vue-i18n';
import { AttemptHttpError, attemptService } from './attempt.service';
import type { AttemptPage, SubmissionAttempt, UploadCapabilities } from './attempt.service';

const props = defineProps<{
  learnerId: string;
  sessionId: string;
  taskId: string;
  exercise?: Exercise;
}>();
const emit = defineEmits<{ 'draft-state': [state: { busy: boolean; dirty: boolean }]; 'capture-state': [state: { contextKey: string; active: boolean }]; 'preview-play': [] }>();
const { t } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const metronomeError = ref(false);
const metronomeBpm = ref(metronome.getConfig().bpm);
const metronomeStatus = ref(metronome.getState().status);
const metronomeRunning = computed(() => metronomeStatus.value === 'running');
const metronomeBusy = computed(() => metronomeStatus.value === 'starting');
const metronomeSource = computed(() => props.exercise ? props.exercise.code + '@' + props.exercise.version : '');
const exerciseMeter = computed(() => props.exercise?.meter ?? '4/4');
let unsubscribeMetronome: (() => void) | undefined;
onMounted(() => {
  unsubscribeMetronome = metronome.subscribe(state => { metronomeStatus.value = state.status; if (metronomeUi.sourceExercise === metronomeSource.value) metronomeBpm.value = (metronome.getPendingConfig() ?? metronome.getConfig()).bpm; });
});
onBeforeUnmount(() => {
  unsubscribeMetronome?.();
  if (metronomeUi.sourceExercise === metronomeSource.value) {
    metronome.stop();
    metronomeUi.sourceExercise = null;
  }
});
watch(() => [props.exercise?.code, props.exercise?.version, props.exercise?.bpm], () => {
  metronomeBpm.value = props.exercise?.bpm ?? 100;
  metronomeError.value = false;
});
function setMetronomeBpm(value: number): void {
  if (!Number.isFinite(value)) return;
  const bpm = Math.max(20, Math.min(240, Math.round(value)));
  if (metronomeUi.sourceExercise === metronomeSource.value) {
    if (!metronome.setBpm(bpm)) { metronomeError.value = true; return; }
  }
  metronomeBpm.value = bpm;
}
async function toggleExerciseMetronome(event: MouseEvent): Promise<void> {
  const exercise = props.exercise;
  if (!exercise || metronomeBusy.value) return;
  if (metronomeRunning.value && metronomeUi.sourceExercise === metronomeSource.value) {
    metronome.stop();
    return;
  }
  metronomeError.value = false;
  if (!openExerciseMetronome(event.currentTarget as HTMLElement,
    metronomeSource.value, metronomeBpm.value, exercise.meter, exercise.tempoUnit, false)) {
    metronomeError.value = true;
    return;
  }
  metronomeBpm.value = (metronome.getPendingConfig() ?? metronome.getConfig()).bpm;
  metronomeError.value = !(await metronome.start());
}
const inputMode = ref<'record' | 'file'>('record');
const sourceKind = ref<'record' | 'file' | null>(null);
const canSwitchInput = computed(() => !recordingActive.value && !uploading.value);
function changeInputMode(next: 'record' | 'file'): void {
  if (!canSwitchInput.value || inputMode.value === next) return;
  inputMode.value = next;
}
function inputModeKeys(event: KeyboardEvent): void {
  const next = event.key === 'ArrowRight' || event.key === 'End' ? 'file'
    : event.key === 'ArrowLeft' || event.key === 'Home' ? 'record' : null;
  if (!next || !canSwitchInput.value) return;
  event.preventDefault();
  changeInputMode(next);
  const buttons = (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]');
  buttons[next === 'record' ? 0 : 1]?.focus();
}
function removeDraft(): void {
  if (uploading.value || recordingActive.value) return;
  lastSubmitted = null;
  file.value = null;
  sourceKind.value = null;
  clearRecordedPreview();
  if (fileInput.value) fileInput.value.value = '';
  requestId.value = crypto.randomUUID();
  notice.value = '';
  error.value = '';
}

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
const playbackElement = ref<HTMLAudioElement | null>(null);
const playbackActive = ref(false);
async function togglePlayback(): Promise<void> {
  const audio = playbackElement.value;
  if (!audio || !recordedPreview.value || recordingActive.value || uploading.value) return;
  if (!audio.paused) { audio.pause(); playbackActive.value = false; return; }
  emit('preview-play');
  try { await audio.play(); playbackActive.value = true; }
  catch { playbackActive.value = false; }
}

function clearRecordedPreview() {
  playbackElement.value?.pause();
  playbackActive.value = false;
  if (recordedPreview.value) URL.revokeObjectURL(recordedPreview.value);
  recordedPreview.value = null;
}

function onRecordingActive(active: boolean) {
  recordingActive.value = active;
  if (active) { playbackElement.value?.pause(); playbackActive.value = false; }
  emit('capture-state', { contextKey: JSON.stringify([props.learnerId, props.sessionId, props.taskId]), active });
}

function onRecorded(recording: File) {
  clearRecordedPreview();
  if (fileInput.value) fileInput.value.value = '';
  file.value = recording;
  sourceKind.value = 'record';
  recordedPreview.value = URL.createObjectURL(recording);
  requestId.value = crypto.randomUUID();
  lastSubmitted = null;
  notice.value = '';
  error.value = '';
}
const comment = ref('');
const requestId = ref(crypto.randomUUID());
// An idempotency key belongs to exactly one immutable upload payload.
// Retrying without edits preserves it; editing after an attempt rotates it.
let lastSubmitted: { file: File; comment: string; requestId: string } | null = null;
watch(comment, value => {
  if (lastSubmitted && !uploading.value && value !== lastSubmitted.comment) {
    requestId.value = crypto.randomUUID();
    lastSubmitted = null;
  }
});
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
  sourceKind.value = null;
  inputMode.value = 'record';
  if (fileInput.value) fileInput.value.value = '';
  clearRecordedPreview();
  comment.value = '';
  requestId.value = crypto.randomUUID();
  lastSubmitted = null;
  recordingActive.value = false;
  uploading.value = false;
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
  const input = event.target as HTMLInputElement;
  const chosen = input.files?.[0] ?? null;
  if (!chosen || recordingActive.value || uploading.value) return;
  const max = capabilities.value?.maxBytes ?? 20971520;
  if (chosen.size === 0 || chosen.size > max || !/\.(mp3|wav|ogg|webm|m4a)$/i.test(chosen.name)) {
    error.value = chosen.size === 0 || chosen.size > max ? t('attempt.sizeError') : t('attempt.formatError');
    input.value = '';
    return;
  }
  clearRecordedPreview();
  file.value = chosen;
  sourceKind.value = 'file';
  recordedPreview.value = URL.createObjectURL(chosen);
  requestId.value = crypto.randomUUID();
  lastSubmitted = null;
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
  const snapshot = { chosen, comment: comment.value, requestId: requestId.value };
  lastSubmitted = { file: snapshot.chosen, comment: snapshot.comment, requestId: snapshot.requestId };
  uploading.value = true;
  error.value = '';
  notice.value = '';
  try {
    await attemptService.upload(learnerId, sessionId, taskId,
      snapshot.chosen, snapshot.comment, snapshot.requestId);
    if (!currentContext(generation)) return;
    file.value = null;
    sourceKind.value = null;
    clearRecordedPreview();
    // File inputs are native browser controls: clearing Vue state alone does not reset their label.
    if (fileInput.value) fileInput.value.value = '';
    comment.value = '';
    requestId.value = crypto.randomUUID();
    lastSubmitted = null;
    notice.value = t('attempt.uploaded');
    await navigate(undefined, 'latest');
  } catch (cause) {
    if (currentContext(generation)) error.value = uploadErrorMessage(cause);
    // Retain the selected file and idempotency key; user can retry or pick another file.
  } finally {
    if (currentContext(generation)) uploading.value = false;
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
  emit('capture-state', { contextKey: JSON.stringify([props.learnerId, props.sessionId, props.taskId]), active: false });
});
</script>

<template>
  <section class="homework-panel" :class="{ 'studio-workspace-panel': lightMinimal }" aria-labelledby="homework-heading">
    <div v-if="loading" class="hint">{{ t('attempt.loading') }}</div>
    <template v-else>
    <div :class="{ 'studio-record-card': lightMinimal }">
    <div class="homework-heading">
      <h3 id="homework-heading"><svg v-if="lightMinimal" class="studio-mic-icon" width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/></svg>{{ lightMinimal ? t('attempt.recordingPanelTitle') : t('attempt.title') }}</h3>
      <p v-if="!lightMinimal" class="hint">{{ t('attempt.subtitle') }}</p>
    </div>
      <p v-if="!capabilities?.enabled" class="hint" role="status">
        {{ t('attempt.disabledHelp') }}
      </p>
      <p v-if="!capabilities?.enabled && lightMinimal" class="hint">
        {{ t('attempt.localRecordingAvailable') }}
      </p>
      <p v-if="learningTerminal" class="hint" role="status">{{ t('learning.uploadClosed') }}</p>
      <form class="homework-form" @submit.prevent="submit">
        <div v-if="lightMinimal" class="recording-mode-tabs" role="tablist"
          :aria-label="t('attempt.inputModeLabel')" @keydown="inputModeKeys">
          <button type="button" role="tab" id="attempt-tab-record" aria-controls="attempt-panel-record" :aria-selected="inputMode === 'record'"
            :tabindex="inputMode === 'record' ? 0 : -1" :disabled="!canSwitchInput"
            @click="changeInputMode('record')"><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/></svg>{{ t('attempt.recordTab') }}</button>
          <button type="button" role="tab" id="attempt-tab-file" aria-controls="attempt-panel-file" :aria-selected="inputMode === 'file'"
            :tabindex="inputMode === 'file' ? 0 : -1" :disabled="!canSwitchInput"
            @click="changeInputMode('file')"><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M4 17v3h16v-3"/></svg>{{ t('attempt.fileTab') }}</button>
        </div>
        <div v-if="lightMinimal" class="recording-primary" :class="{ 'input-mode-hidden': inputMode !== 'record' }"
          id="attempt-panel-record" role="tabpanel" aria-labelledby="attempt-tab-record">
          <h4>{{ t('attempt.microphoneTitle') }}</h4>
          <MicrophoneRecorder :compact="lightMinimal" :disabled="learningTerminal || uploading"
          :max-bytes="capabilities?.maxBytes ?? 20971520"
          @recorded="onRecorded" @active="onRecordingActive">
            <template #actions>
              <audio v-if="file && recordedPreview" ref="playbackElement" class="studio-playback-source"
                :src="recordedPreview" preload="metadata" @ended="playbackActive = false" @pause="playbackActive = false" />
              <button type="button" class="studio-play-button" :disabled="!file || !recordedPreview || recordingActive || uploading"
                :aria-label="playbackActive ? t('attempt.pausePlayback') : t('attempt.listenRecording')"
                @click="togglePlayback">
                <svg v-if="playbackActive" width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5.5a1 1 0 0 1 1.53-.85l10 6.5a1 1 0 0 1 0 1.7l-10 6.5A1 1 0 0 1 8 18.5z"/></svg>
                <span>{{ playbackActive ? t('attempt.pausePlayback') : t('attempt.listenShort') }}</span>
              </button>
              <button type="submit" class="recorder-button studio-send-button"
                :disabled="learningTerminal || !file || !capabilities?.enabled || uploading || recordingActive">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 3 10 14m11-11-7 18-4-7-7-4 18-7Z"/></svg>
                <span>{{ uploading ? t('attempt.uploading') : t('attempt.submitShort') }}</span>
              </button>
            </template>
          </MicrophoneRecorder>
        </div>
        <div v-if="lightMinimal" class="recording-file-option"
          :class="{ 'input-mode-hidden': inputMode !== 'file' }"
          id="attempt-panel-file" role="tabpanel" aria-labelledby="attempt-tab-file">
          <input id="attempt-file" ref="fileInput" class="studio-file-input" type="file"
            accept=".mp3,.wav,.ogg,.webm,.m4a,audio/*"
            :disabled="learningTerminal || uploading || recordingActive"
            @change="selectFile" />
          <label class="studio-file-picker" for="attempt-file">
            <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M4 17v3h16v-3"/></svg>
            <strong>{{ t('attempt.chooseFile') }}</strong>
          </label>
          <p class="hint">{{ t('attempt.fileLimits') }}</p>
        </div>
        <div v-if="lightMinimal && file" class="studio-selected-file" role="status">
          <span class="studio-attachment-info"><strong>{{ file.name }}</strong>
          <small>{{ fileSize(file.size) }} · {{ sourceKind === 'record' ? t('attempt.recordTab') : t('attempt.fileTab') }}</small></span>
          <button type="button" class="studio-discard-button" :disabled="uploading || recordingActive" @click="removeDraft">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2m3 0-1 15H6L5 6m5 4v7m4-7v7"/></svg>
            {{ t('attempt.removeAttachment') }}
          </button>
        </div>
        <button v-if="lightMinimal && inputMode === 'file'" type="submit" class="studio-file-submit"
          :disabled="learningTerminal || !file || !capabilities?.enabled || uploading || recordingActive">
          {{ uploading ? t('attempt.uploading') : t('attempt.submitShort') }}
        </button>
        <template v-if="!lightMinimal">
          <label for="attempt-file">{{ t('attempt.fileLabel') }}</label>
        <input id="attempt-file" ref="fileInput" type="file" accept=".mp3,.wav,.ogg,.webm,.m4a,audio/*"
          :disabled="learningTerminal || !capabilities?.enabled || uploading || recordingActive" @change="selectFile" />
        <p class="hint">{{ t('attempt.fileLimits') }}</p>
          <MicrophoneRecorder :compact="lightMinimal" :disabled="learningTerminal || uploading"
          :max-bytes="capabilities?.maxBytes ?? 20971520"
          @recorded="onRecorded" @active="onRecordingActive" />
        </template>
        <div v-if="recordedPreview && file && !lightMinimal" class="recorded-preview">
          <strong>{{ t('attempt.recordingReady') }} ({{ fileSize(file.size) }})</strong>
          <audio :src="recordedPreview" controls preload="metadata"
            :aria-label="t('attempt.listenRecording')" />
          <button type="button" class="recorder-button secondary-button"
            :disabled="uploading || recordingActive" @click="removeDraft">
            {{ t('attempt.discardRecording') }}
          </button>
        </div>
        <label for="attempt-comment" :class="{ 'studio-secondary-label': lightMinimal }">{{ t('attempt.commentLabel') }}</label>
        <textarea id="attempt-comment" v-model="comment" rows="3" maxlength="2000"
          :disabled="uploading || recordingActive"
          :placeholder="t('attempt.commentPlaceholder')" />
        <div v-if="!lightMinimal" class="form-actions">
          <button type="submit" class="action-button"
            :disabled="learningTerminal || !file || !capabilities?.enabled || uploading || recordingActive">
            {{ uploading ? t('attempt.uploading') : t('attempt.submit') }}
          </button>
        </div>
      </form>
      <p v-if="notice" role="status" class="saved-status">{{ notice }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>

    <section v-if="lightMinimal && exercise" class="studio-metronome-card" :aria-label="t('metronome.title')">
      <div class="studio-metronome-heading">
        <svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M9 3h6l5 18H4L9 3Z"/><path d="m12 17 4-10"/>
        </svg>
        <h3>{{ t('metronome.title') }}</h3>
      </div>
      <div class="studio-metronome-controls">
        <div class="studio-metronome-setting studio-bpm-input">
          <label for="exercise-metronome-bpm">BPM</label>
          <button type="button" :disabled="metronomeBusy" :aria-label="t('metronome.decreaseBpm')"
            @click="setMetronomeBpm(metronomeBpm - 1)">−</button>
          <input id="exercise-metronome-bpm" type="number" min="20" max="240" step="1"
            :value="metronomeBpm" :disabled="metronomeBusy"
            @change="setMetronomeBpm(Number(($event.target as HTMLInputElement).value))" />
          <button type="button" :disabled="metronomeBusy" :aria-label="t('metronome.increaseBpm')"
            @click="setMetronomeBpm(metronomeBpm + 1)">+</button>
        </div>
        <span class="studio-metronome-setting"><small>{{ t('diagnostic.metadata.tempo') }}</small><strong>{{ exerciseMeter }}</strong></span>
        <button type="button" class="studio-metronome-launch" :disabled="metronomeBusy"
          @click="toggleExerciseMetronome" :aria-pressed="metronomeRunning && metronomeUi.sourceExercise === metronomeSource"
          :aria-label="metronomeRunning && metronomeUi.sourceExercise === metronomeSource ? t('metronome.stop') : t('metronome.start')">
          <svg v-if="metronomeRunning && metronomeUi.sourceExercise === metronomeSource" width="19" height="19" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="5" y="5" width="14" height="14" rx="2"/></svg>
          <svg v-else width="19" height="19" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5.5a1 1 0 0 1 1.53-.85l10 6.5a1 1 0 0 1 0 1.7l-10 6.5A1 1 0 0 1 8 18.5z"/></svg>
          {{ metronomeRunning && metronomeUi.sourceExercise === metronomeSource ? t('metronome.stop') : t('metronome.start') }}
        </button>
      </div>
      <p v-if="metronomeError" role="alert" class="error">{{ t('metronome.exerciseMeterUnavailable') }}</p>
    </section>
    <div :class="{ 'studio-result-card': lightMinimal }">
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
    </div>
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
.homework-panel:has(.recording-primary) .studio-send-button {border:1px solid #28776d!important;background:#28776d!important;color:white!important;padding:10px 16px!important;min-width:110px}
.homework-panel:has(.recording-primary) .studio-send-button:disabled {opacity:.45;cursor:not-allowed}
.homework-panel:has(.recording-primary) .studio-results-divider {border-top:1px solid #edf1ed;margin:18px 0 12px}


.homework-panel:has(.recording-primary) .studio-mic-icon {flex:none;color:#162331}
.homework-panel:has(.recording-primary) .homework-heading h3 {display:flex;align-items:center;gap:9px}
.homework-panel:has(.recording-primary) .studio-playback-source {display:none}
.homework-panel:has(.recording-primary) .studio-play-button,
.homework-panel:has(.recording-primary) .studio-send-button {display:inline-flex;align-items:center;justify-content:center;gap:7px;min-width:0;flex:1 1 0;white-space:nowrap;font-size:13px;padding:9px 12px;border-radius:999px;min-height:44px}
.homework-panel:has(.recording-primary) .studio-play-button {border:1px solid #dfe7e3;background:#fff;color:#243a35;cursor:pointer}
.homework-panel:has(.recording-primary) .studio-play-button:disabled {opacity:.45;cursor:not-allowed}
.homework-panel:has(.recording-primary) .studio-play-button:focus-visible,
.homework-panel:has(.recording-primary) .studio-send-button:focus-visible {outline:3px solid #28776d;outline-offset:2px}

.homework-panel:has(.recording-primary) .studio-discard-button {align-self:flex-start;border:0;background:transparent;color:#526e64;text-decoration:underline;font-size:12px;min-height:34px;cursor:pointer}
.homework-panel:has(.recording-primary) .studio-discard-button:focus-visible {outline:2px solid #28776d}

/* #150: two real independent card surfaces, with a single shared form/state. */
.studio-workspace-panel {display:flex;flex-direction:column;gap:16px!important;border:0!important;padding:0!important;background:transparent!important;box-shadow:none!important}
.studio-record-card,.studio-result-card {background:#fff;border:1px solid #e0e7e4;border-radius:16px;padding:18px;box-shadow:0 5px 24px #16233108;min-width:0}
.studio-workspace-panel .studio-result-card :deep(.homework-history) {margin:0}
.studio-workspace-panel .studio-result-card :deep(.action-button.secondary-button) {background:#f6f9f7;color:#28574a;border:1px solid #dbe6df}
.studio-workspace-panel .studio-result-card :deep(details > summary) {cursor:pointer;display:flex;align-items:center;min-height:44px;padding:8px 2px;border-radius:8px}
.studio-workspace-panel .studio-result-card :deep(details > summary:hover) {background:#f4f8f5}
.studio-workspace-panel .studio-result-card :deep(details > summary:focus-visible) {outline:3px solid #28776d;outline-offset:2px}
.recording-mode-tabs {display:grid;grid-template-columns:1fr 1fr;padding:4px;border-radius:12px;background:#f4f7f5;gap:4px}
.recording-mode-tabs button {min-height:44px;min-width:0;padding:9px 5px;display:inline-flex;align-items:center;justify-content:center;gap:7px;border:0;border-radius:9px;background:transparent;color:#54645f;font:inherit;font-size:12px;font-weight:600;cursor:pointer}
.recording-mode-tabs button[aria-selected=true] {background:#fff;color:#246a56;box-shadow:0 1px 4px #16233118}
.recording-mode-tabs button:disabled {opacity:.6;cursor:not-allowed}
.recording-mode-tabs button:focus-visible {outline:3px solid #28776d;outline-offset:2px}
.input-mode-hidden {display:none!important}
.studio-workspace-panel .recording-file-option {border:0;margin:0;padding:4px 0 0}
.studio-file-input {position:absolute;width:1px;height:1px;opacity:0;overflow:hidden;clip-path:inset(50%)}
.studio-file-picker {cursor:pointer;min-height:84px;border:1px dashed #94b9a6;border-radius:12px;background:#f7faf8;display:flex;align-items:center;justify-content:center;gap:10px;color:#276c54;padding:12px;text-align:center}
.studio-file-input:focus-visible + .studio-file-picker {outline:3px solid #28776d;outline-offset:2px}
.studio-file-input:disabled + .studio-file-picker {cursor:not-allowed;opacity:.45}
.studio-record-card .studio-selected-file {display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;background:#f5f9f6;border:1px solid #e1eae3;border-radius:10px;min-width:0}
.studio-attachment-info {min-width:0;display:flex;flex-direction:column;gap:3px;overflow-wrap:anywhere}
.studio-attachment-info strong {font-size:12px}
.studio-attachment-info small {font-size:11px;color:#6d7d76}
.studio-workspace-panel .studio-discard-button {min-height:44px;display:inline-flex;align-items:center;justify-content:center;gap:5px;align-self:auto;flex:none;padding:8px 10px;border:1px solid #dce6e0;border-radius:9px;background:#fff;color:#566c64;font-size:12px;text-decoration:none;cursor:pointer}
.studio-file-submit {min-height:44px;border:0;border-radius:10px;background:#28776d;color:white;font-weight:650;cursor:pointer}
.studio-file-submit:disabled {opacity:.45;cursor:not-allowed}
.studio-result-card > details.homework-history > summary {min-height:44px;padding:10px 8px;cursor:pointer;border-radius:9px;display:flex;align-items:center;justify-content:space-between;list-style:none;font-weight:600}
.studio-result-card > details.homework-history > summary::-webkit-details-marker {display:none}
.studio-result-card > details.homework-history > summary::after {content:"";width:8px;height:8px;border-right:2px solid #52685e;border-bottom:2px solid #52685e;transform:rotate(45deg);transition:transform .15s;flex:none;margin-right:8px}
.studio-result-card > details.homework-history[open] > summary::after {transform:rotate(225deg)}
.studio-result-card > details.homework-history > summary:hover {background:#f4f8f5}
.studio-result-card > details.homework-history > summary:focus-visible {outline:3px solid #28776d}
.studio-record-card .recording-primary {margin:0}
@media(max-width:430px) {.studio-record-card,.studio-result-card {padding:13px}.studio-record-card .studio-selected-file {flex-wrap:wrap}}

.studio-metronome-card {background:#fff;border:1px solid #e0e7e4;border-radius:16px;padding:16px 18px;box-shadow:0 5px 24px #16233108;min-width:0}
.studio-metronome-heading {display:flex;align-items:center;gap:9px;margin-bottom:12px}
.studio-metronome-heading h3 {margin:0;font-size:16px;color:#172333}
.studio-metronome-controls {display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.studio-metronome-setting {display:flex;align-items:center;gap:8px;padding:9px 12px;background:#f6f9f7;border:1px solid #e0e8e3;border-radius:9px;min-height:42px}
.studio-bpm-input {gap:5px;padding:5px 7px}
.studio-bpm-input label {font-size:12px;color:#75847c;margin-right:4px}
.studio-bpm-input input {width:47px;border:0;background:transparent;text-align:center;font:inherit;font-size:15px;font-weight:700;color:#172333;appearance:textfield}
.studio-bpm-input input::-webkit-inner-spin-button,.studio-bpm-input input::-webkit-outer-spin-button {appearance:none;margin:0}
.studio-bpm-input button {height:32px;min-width:30px;border:1px solid #e1e8e4;border-radius:8px;background:white;cursor:pointer;font-size:17px}
.studio-metronome-setting small {font-size:12px;color:#75847c}
.studio-metronome-setting strong {font-size:15px;font-variant-numeric:tabular-nums}
.studio-metronome-launch {display:inline-flex;align-items:center;justify-content:center;gap:8px;margin-left:auto;min-height:44px;padding:9px 14px;border-radius:10px;border:0;color:#fff;background:#28776d;font-weight:600;cursor:pointer}
.studio-metronome-launch:focus-visible {outline:3px solid #28776d;outline-offset:3px}
@media(max-width:380px) {.studio-metronome-launch {width:100%;margin-left:0}}
</style>
