<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { attemptService } from './attempt.service';
import type { SubmissionAttempt, UploadCapabilities } from './attempt.service';

const props = defineProps<{
  learnerId: string;
  sessionId: string;
  taskId: string;
}>();
const { t } = useI18n();
const attempts = ref<SubmissionAttempt[]>([]);
const capabilities = ref<UploadCapabilities | null>(null);
const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const comment = ref('');
const requestId = ref(crypto.randomUUID());
const loading = ref(true);
const uploading = ref(false);
const error = ref('');
const notice = ref('');

function selectFile(event: Event) {
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
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'UNKNOWN_ERROR';
  } finally {
    loading.value = false;
  }
}
async function submit() {
  const chosen = file.value;
  if (!chosen || !capabilities.value?.enabled || uploading.value) return;
  if (chosen.size === 0 || chosen.size > capabilities.value.maxBytes) {
    error.value = t('attempt.sizeError');
    return;
  }
  if (!/\.(mp3|wav|ogg|webm)$/i.test(chosen.name)) {
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
    // File inputs are native browser controls: clearing Vue state alone does not reset their label.
    if (fileInput.value) fileInput.value.value = '';
    comment.value = '';
    requestId.value = crypto.randomUUID();
    notice.value = t('attempt.uploaded');
    await refresh();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'UNKNOWN_ERROR';
    // Keep requestId for idempotent retry if the network fails after storage.
  } finally {
    uploading.value = false;
  }
}
function fileSize(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}
onMounted(refresh);
</script>

<template>
  <section class="homework-panel" aria-labelledby="homework-heading">
    <div class="homework-heading">
      <h3 id="homework-heading">{{ t('attempt.title') }}</h3>
      <p class="hint">{{ t('attempt.subtitle') }}</p>
    </div>
    <div v-if="loading" class="hint">{{ t('attempt.loading') }}</div>
    <template v-else>
      <div class="homework-status" role="note">
        {{ t('attempt.noAiFeedback') }}
      </div>
      <p v-if="!capabilities?.enabled" class="hint">
        {{ t('attempt.disabledHelp') }}
      </p>
      <form class="homework-form" @submit.prevent="submit">
        <label for="attempt-file">{{ t('attempt.fileLabel') }}</label>
        <input id="attempt-file" ref="fileInput" type="file" accept=".mp3,.wav,.ogg,.webm,audio/*"
          :disabled="!capabilities?.enabled || uploading" @change="selectFile" />
        <p class="hint">{{ t('attempt.fileLimits') }}</p>
        <label for="attempt-comment">{{ t('attempt.commentLabel') }}</label>
        <textarea id="attempt-comment" v-model="comment" rows="3" maxlength="2000"
          :disabled="!capabilities?.enabled || uploading"
          :placeholder="t('attempt.commentPlaceholder')" />
        <div class="form-actions">
          <button type="submit" class="action-button"
            :disabled="!file || !capabilities?.enabled || uploading">
            {{ uploading ? t('attempt.uploading') : t('attempt.submit') }}
          </button>
        </div>
      </form>
      <p v-if="notice" role="status" class="saved-status">{{ notice }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
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
          <p class="attempt-unreviewed">{{ t('attempt.receivedUnassessed') }}</p>
        </article>
      </div>
    </template>
  </section>
</template>
