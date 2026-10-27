<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import MicrophoneRecorder from '../attempt/MicrophoneRecorder.vue';
import { evidenceService } from './evidence.service';
import type { EvidenceEntry, EvidenceCapabilities } from './evidence.service';

const props = defineProps<{ learnerId: string; sessionId: string; allowUpload: boolean }>();
const emit = defineEmits<{
  (event: 'intake-state', state: { busy: boolean; dirty: boolean }): void;
}>();
const { t } = useI18n();
const capabilities = ref<EvidenceCapabilities | null>(null);
const entries = ref<EvidenceEntry[]>([]);
const loading = ref(true);
const uploading = ref(false);
const recordingActive = ref(false);
const error = ref('');
const notice = ref('');
const title = ref('');
const composer = ref('');
const comment = ref('');
const audio = ref<File | null>(null);
const score = ref<File | null>(null);
const audioInput = ref<HTMLInputElement | null>(null);
const scoreInput = ref<HTMLInputElement | null>(null);
const preview = ref<string | null>(null);
const requestId = ref(crypto.randomUUID());
let polling: ReturnType<typeof setInterval> | undefined;
let activeUpload: AbortController | null = null;

/** Aborting a request does not prove the server rolled it back; retry with the SAME requestId. */
function cancelUpload(): void {
  activeUpload?.abort();
}

// A requestId identifies ONE immutable snapshot. A changed form/file is a NEW submission;
// an aborted/failed POST retains its original ID until the user edits the draft.
watch([title, composer, comment, audio, score], () => {
  if (!uploading.value) requestId.value = crypto.randomUUID();
}, { flush: 'sync' });

// Synchronous parent notification closes the gap between upload start and the continue click.
watch([recordingActive, uploading, title, composer, comment, audio, score], () => {
  emit('intake-state', {
    busy: recordingActive.value || uploading.value,
    dirty: !!(title.value.trim() || composer.value.trim() || comment.value.trim()
      || audio.value || score.value),
  });
}, { immediate: true, flush: 'sync' });

function clearPreview(): void {
  if (preview.value) URL.revokeObjectURL(preview.value);
  preview.value = null;
}
function chooseAudio(file: File | null): void {
  clearPreview();
  audio.value = file;
  if (!file && audioInput.value) audioInput.value.value = '';
  if (file) preview.value = URL.createObjectURL(file);
}
function onAudioInput(event: Event): void {
  const target = event.target as HTMLInputElement;
  chooseAudio(target.files?.[0] ?? null);
}
function onScoreInput(event: Event): void {
  score.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}
function removeScore(): void {
  score.value = null;
  if (scoreInput.value) scoreInput.value.value = '';
}
function technicalError(code: string | null | undefined): string {
  if (code === 'DURATION_EXCEEDED') return t('evidence.durationExceeded');
  if (code === 'SIZE_EXCEEDED' || code === 'FILE_TOO_LARGE') return t('evidence.sizeError');
  if (code === 'UNSUPPORTED_FORMAT' || code === 'FORMAT_UNSUPPORTED') return t('evidence.unsupportedFormat');
  return t('evidence.technicalGeneric');
}
function onRecorded(file: File): void {
  if (audioInput.value) audioInput.value.value = '';
  chooseAudio(file);
}
function size(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(2) + ' MiB';
}
async function refresh(): Promise<void> {
  try {
    entries.value = await evidenceService.list(props.learnerId, props.sessionId);
  } catch (cause) {
    if (!loading.value) error.value = cause instanceof Error ? cause.message : 'REQUEST_FAILED';
  }
}
async function initialize(): Promise<void> {
  try {
    capabilities.value = await evidenceService.capabilities(props.learnerId, props.sessionId);
    await refresh();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'REQUEST_FAILED';
  } finally {
    loading.value = false;
  }
}
async function submit(): Promise<void> {
  if (!audio.value || uploading.value || recordingActive.value || !capabilities.value?.enabled) return;
  error.value = '';
  notice.value = '';
  if (!title.value.trim() || title.value.trim().length > 200) {
    error.value = t('evidence.titleInvalid'); return;
  }
  if (audio.value.size > capabilities.value.maxAudioBytes ||
      (score.value && score.value.size > capabilities.value.maxScoreBytes)) {
    error.value = t('evidence.sizeError'); return;
  }
  // Capture one immutable request: edits are disabled during POST.
  uploading.value = true;
  const controller = new AbortController();
  activeUpload = controller;
  try {
    await evidenceService.upload(props.learnerId, props.sessionId, {
      requestId: requestId.value, title: title.value, composer: composer.value,
      comment: comment.value, audio: audio.value, score: score.value,
      signal: controller.signal,
    });
    title.value = '';
    composer.value = '';
    comment.value = '';
    score.value = null;
    chooseAudio(null);
    if (audioInput.value) audioInput.value.value = '';
    if (scoreInput.value) scoreInput.value.value = '';
    requestId.value = crypto.randomUUID();
    notice.value = t('evidence.saved');
  } catch (cause) {
    // Keep the exact files and requestId for safe retry of an unknown outcome.
    const code = cause instanceof Error ? cause.message : 'REQUEST_FAILED';
    error.value = controller.signal.aborted ? t('evidence.uploadCancelledRetry') :
      code === 'IDEMPOTENCY_PAYLOAD_MISMATCH' ? t('evidence.payloadMismatch') : code;
    return;
  } finally {
    activeUpload = null;
    uploading.value = false;
  }
  // POST has completed: never hold navigation hostage to a slow GET history refresh.
  void refresh();
}
onMounted(() => {
  void initialize();
  polling = setInterval(() => { if (!uploading.value) void refresh(); }, 3000);
});
onBeforeUnmount(() => {
  activeUpload?.abort();
  if (polling !== undefined) clearInterval(polling);
  clearPreview();
  emit('intake-state', { busy: false, dirty: false });
});
</script>

<template>
  <!-- The intake form stays open. After moving to task selection, native <details>
       starts closed but keeps the saved evidence available on click or keyboard. -->
  <details class="homework-panel evidence-details evidence-intake" :open="props.allowUpload">
    <summary id="evidence-toggle" class="evidence-summary"
      :style="props.allowUpload ? { display: 'none' } : undefined">
      {{ t('evidence.heading') }} ({{ entries.length }})
    </summary>
    <div :aria-labelledby="props.allowUpload ? 'evidence-heading' : 'evidence-toggle'">
    <h3 v-if="props.allowUpload" id="evidence-heading">{{ t('evidence.heading') }}</h3>
    <p v-if="props.allowUpload" class="hint">{{ t('evidence.description') }}</p>
    <p v-if="loading" role="status">{{ t('evidence.loading') }}</p>
    <template v-else>
      <p v-if="props.allowUpload && !capabilities?.enabled" class="hint">{{ t('evidence.disabled') }}</p>
      <form v-if="props.allowUpload && capabilities?.enabled && entries.length < capabilities.maxItems"
        class="homework-form evidence-form" @submit.prevent="submit">
        <label for="evidence-title">{{ t('evidence.title') }}</label>
        <input id="evidence-title" v-model="title" required maxlength="200"
          :disabled="uploading" :placeholder="t('evidence.titleExample')" />
        <label for="evidence-composer">{{ t('evidence.composer') }}</label>
        <input id="evidence-composer" v-model="composer" maxlength="200" :disabled="uploading" />
        <label for="evidence-audio">{{ t('evidence.audio') }}</label>
        <div class="evidence-file-picker" :class="{ 'has-file': !!audio }">
          <input id="evidence-audio" ref="audioInput" class="evidence-file-input" type="file"
            accept=".mp3,.wav,.ogg,.webm,.m4a" :disabled="uploading || recordingActive" @change="onAudioInput" />
          <div v-if="audio" class="evidence-file-selected" role="status" aria-live="polite">
            <span class="evidence-file-check" aria-hidden="true">✓</span>
            <span class="evidence-file-meta">
              <strong class="evidence-file-name">{{ audio.name }}</strong>
              <span class="evidence-file-secondary">{{ t('evidence.attached') }} · {{ size(audio.size) }}</span>
            </span>
          </div>
          <span v-else class="evidence-file-empty">{{ t('evidence.audioFormats') }}</span>
          <div class="evidence-file-actions">
            <label class="evidence-file-trigger" for="evidence-audio">{{ audio ? t('evidence.replaceFile') : t('evidence.chooseAudio') }}</label>
            <button v-if="audio" type="button" class="evidence-file-remove"
              :disabled="uploading || recordingActive" @click="chooseAudio(null)">{{ t('evidence.removeFile') }}</button>
          </div>
        </div>
        <MicrophoneRecorder :disabled="uploading" :max-bytes="capabilities.maxAudioBytes"
          @active="recordingActive = $event" @recorded="onRecorded" />
        <div v-if="preview" class="recorded-preview">
          <audio :src="preview" controls preload="metadata" :aria-label="t('evidence.listen')" />
          <button type="button" class="recorder-button secondary-button"
            :disabled="uploading || recordingActive" @click="chooseAudio(null)">
            {{ t('evidence.discard') }}
          </button>
        </div>
        <label for="evidence-score">{{ t('evidence.score') }}</label>
        <div class="evidence-file-picker" :class="{ 'has-file': !!score }">
          <input id="evidence-score" ref="scoreInput" class="evidence-file-input" type="file"
            accept=".pdf,.jpg,.jpeg,.png" :disabled="uploading" @change="onScoreInput" />
          <div v-if="score" class="evidence-file-selected" role="status" aria-live="polite">
            <span class="evidence-file-check" aria-hidden="true">✓</span>
            <span class="evidence-file-meta">
              <strong class="evidence-file-name">{{ score.name }}</strong>
              <span class="evidence-file-secondary">{{ t('evidence.attached') }} · {{ size(score.size) }}</span>
            </span>
          </div>
          <span v-else class="evidence-file-empty">{{ t('evidence.scoreFormats') }}</span>
          <div class="evidence-file-actions">
            <label class="evidence-file-trigger" for="evidence-score">{{ score ? t('evidence.replaceFile') : t('evidence.chooseScore') }}</label>
            <button v-if="score" type="button" class="evidence-file-remove"
              :disabled="uploading" @click="removeScore">{{ t('evidence.removeFile') }}</button>
          </div>
        </div>
        <p class="hint">{{ t('evidence.limits') }}</p>
        <label for="evidence-comment">{{ t('evidence.comment') }}</label>
        <textarea id="evidence-comment" v-model="comment" rows="2" maxlength="2000"
          :disabled="uploading" />
        <button type="submit" class="action-button"
          :disabled="uploading || recordingActive || !audio || !title.trim()">
          {{ uploading ? t('evidence.uploading') : t('evidence.add') }}
        </button>
        <button v-if="uploading" type="button" class="recorder-button secondary-button"
          @click="cancelUpload">{{ t('evidence.cancelUpload') }}</button>
      </form>
      <p v-else-if="props.allowUpload && capabilities?.enabled" class="hint">{{ t('evidence.limitReached') }}</p>
      <p v-if="notice" class="saved-status" role="status">{{ notice }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <h4>{{ t('evidence.listHeading') }} ({{ entries.length }})</h4>
      <p v-if="entries.length === 0" class="hint">{{ t('evidence.empty') }}</p>
      <article v-for="entry in entries" :key="entry.id" class="attempt-card">
        <h4>{{ entry.title }}</h4>
        <p v-if="entry.composer">{{ entry.composer }}</p>
        <p v-if="entry.comment">{{ entry.comment }}</p>
        <p class="hint">{{ new Date(entry.submittedAt).toLocaleString() }}</p>
        <p>{{ t('evidence.audio') }}: {{ entry.audio.format.toUpperCase() }} · {{ size(entry.audio.bytes) }}</p>
        <p v-if="entry.score">
          {{ t('evidence.score') }}: {{ entry.score.format.toUpperCase() }} · {{ size(entry.score.bytes) }}
        </p>
        <p v-if="entry.audio.status === 'FAILED'" class="hint" role="status">
          {{ t('evidence.technicalFailed') }}: {{ technicalError(entry.audio.errorCode) }}
        </p>
        <p v-else class="hint" role="status">
          {{ t('evidence.technicalStatus') }}: {{ entry.audio.status }}
        </p>
        <p v-if="entry.audio.mediaState === 'DELETED' || entry.score?.mediaState === 'DELETED'"
          class="hint">{{ t('evidence.deleted') }}</p>
        <p class="hint">{{ t('evidence.notAssessed') }}</p>
      </article>
    </template>
    </div>
  </details>
</template>
