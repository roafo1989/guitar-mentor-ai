<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import MicrophoneRecorder from '../attempt/MicrophoneRecorder.vue';
import { evidenceService } from './evidence.service';
import type { EvidenceEntry, EvidenceCapabilities } from './evidence.service';

const props = defineProps<{ learnerId: string; sessionId: string }>();
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

function clearPreview(): void {
  if (preview.value) URL.revokeObjectURL(preview.value);
  preview.value = null;
}
function chooseAudio(file: File | null): void {
  clearPreview();
  audio.value = file;
  if (file) preview.value = URL.createObjectURL(file);
}
function onAudioInput(event: Event): void {
  const target = event.target as HTMLInputElement;
  chooseAudio(target.files?.[0] ?? null);
}
function onScoreInput(event: Event): void {
  score.value = (event.target as HTMLInputElement).files?.[0] ?? null;
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
  uploading.value = true;
  try {
    await evidenceService.upload(props.learnerId, props.sessionId, {
      requestId: requestId.value, title: title.value, composer: composer.value,
      comment: comment.value, audio: audio.value, score: score.value,
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
    await refresh();
  } catch (cause) {
    // Keep both files and requestId: a retry must be idempotent.
    error.value = cause instanceof Error ? cause.message : 'REQUEST_FAILED';
  } finally {
    uploading.value = false;
  }
}
onMounted(() => {
  void initialize();
  polling = setInterval(() => { if (!uploading.value) void refresh(); }, 3000);
});
onBeforeUnmount(() => {
  if (polling !== undefined) clearInterval(polling);
  clearPreview();
});
</script>

<template>
  <section class="homework-panel" aria-labelledby="evidence-heading">
    <h3 id="evidence-heading">{{ t('evidence.heading') }}</h3>
    <p class="hint">{{ t('evidence.description') }}</p>
    <p v-if="loading" role="status">{{ t('evidence.loading') }}</p>
    <template v-else>
      <p v-if="!capabilities?.enabled" class="hint">{{ t('evidence.disabled') }}</p>
      <form v-if="capabilities?.enabled && entries.length < capabilities.maxItems"
        class="homework-form" @submit.prevent="submit">
        <label for="evidence-title">{{ t('evidence.title') }}</label>
        <input id="evidence-title" v-model="title" required maxlength="200"
          :disabled="uploading" :placeholder="t('evidence.titleExample')" />
        <label for="evidence-composer">{{ t('evidence.composer') }}</label>
        <input id="evidence-composer" v-model="composer" maxlength="200" :disabled="uploading" />
        <label for="evidence-audio">{{ t('evidence.audio') }}</label>
        <input id="evidence-audio" ref="audioInput" type="file"
          accept=".mp3,.wav,.ogg,.webm" :disabled="uploading || recordingActive" @change="onAudioInput" />
        <MicrophoneRecorder :disabled="uploading" :max-bytes="capabilities.maxAudioBytes"
          @active="recordingActive = $event" @recorded="onRecorded" />
        <div v-if="preview" class="recorded-preview">
          <audio :src="preview" controls preload="metadata" :aria-label="t('evidence.listen')" />
          <button type="button" class="recorder-button secondary-button"
            :disabled="uploading" @click="chooseAudio(null)">
            {{ t('evidence.discard') }}
          </button>
        </div>
        <label for="evidence-score">{{ t('evidence.score') }}</label>
        <input id="evidence-score" ref="scoreInput" type="file"
          accept=".pdf,.jpg,.jpeg,.png" :disabled="uploading" @change="onScoreInput" />
        <p class="hint">{{ t('evidence.limits') }}</p>
        <label for="evidence-comment">{{ t('evidence.comment') }}</label>
        <textarea id="evidence-comment" v-model="comment" rows="2" maxlength="2000"
          :disabled="uploading" />
        <button type="submit" class="action-button"
          :disabled="uploading || recordingActive || !audio || !title.trim()">
          {{ uploading ? t('evidence.uploading') : t('evidence.add') }}
        </button>
      </form>
      <p v-else-if="capabilities?.enabled" class="hint">{{ t('evidence.limitReached') }}</p>
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
          {{ t('evidence.technicalFailed') }}: {{ entry.audio.errorCode }}
        </p>
        <p v-else class="hint" role="status">
          {{ t('evidence.technicalStatus') }}: {{ entry.audio.status }}
        </p>
        <p v-if="entry.audio.mediaState === 'DELETED' || entry.score?.mediaState === 'DELETED'"
          class="hint">{{ t('evidence.deleted') }}</p>
        <p class="hint">{{ t('evidence.notAssessed') }}</p>
      </article>
    </template>
  </section>
</template>
