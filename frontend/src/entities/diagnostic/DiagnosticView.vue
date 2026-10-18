<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { diagnosticService } from './diagnostic.service';
import type { DiagnosticSession } from '../../shared/model/diagnostic.model';

const { t } = useI18n();
const learnerStore = useLearnerStore();
const session = ref<DiagnosticSession | null>(null);
const loading = ref(true);
const busy = ref(false);
const error = ref('');
const instrumentCode = 'ACOUSTIC_GUITAR';

function storageKey(learnerId: string): string {
  return 'guitar-mentor.diagnostic.' + learnerId + '.' + instrumentCode;
}

function showError(cause: unknown): string {
  return cause instanceof Error && cause.message === 'NETWORK_UNAVAILABLE'
    ? t('form.networkError') : cause instanceof Error ? cause.message : 'UNKNOWN_ERROR';
}

onMounted(async () => {
  try {
    if (!learnerStore.learner) await learnerStore.restore();
    const id = learnerStore.learner?.id;
    if (!id) return;
    const stored = window.localStorage.getItem(storageKey(id));
    if (!stored) return;
    try {
      session.value = await diagnosticService.get(id, stored);
    } catch (cause) {
      if (cause instanceof Error && cause.message.startsWith('HTTP_404')) {
        window.localStorage.removeItem(storageKey(id));
      } else {
        throw cause;
      }
    }
  } catch (cause) {
    error.value = showError(cause);
  } finally {
    loading.value = false;
  }
});

async function start(): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    session.value = await diagnosticService.start(id, instrumentCode);
    window.localStorage.setItem(storageKey(id), session.value.id);
  } catch (cause) {
    error.value = showError(cause);
  } finally {
    busy.value = false;
  }
}

async function continueWithoutRecording(): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || !session.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    session.value = await diagnosticService.continueWithoutRecording(id, session.value.id);
  } catch (cause) {
    error.value = showError(cause);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="profile-page">
    <div class="intro">
      <span class="eyebrow">DEV-05A · DIAGNOSTIC SESSION</span>
      <h1>{{ t('diagnostic.title') }}</h1>
      <p>{{ t('diagnostic.subtitle') }}</p>
    </div>
    <div class="notice" role="note">{{ t('form.notice') }}</div>
    <div v-if="loading" class="panel">{{ t('diagnostic.loading') }}</div>
    <div v-else-if="!learnerStore.learner" class="panel">
      <p>{{ t('diagnostic.needProfile') }}</p>
      <RouterLink to="/profile">{{ t('app.profile') }}</RouterLink>
    </div>
    <div v-else class="panel">
      <div v-if="session" class="saved-status" role="status">
        {{ t('diagnostic.session') }}: {{ session.id }}
      </div>
      <template v-if="!session">
        <h2>{{ t('diagnostic.startTitle') }}</h2>
        <p>{{ t('diagnostic.startDescription') }}</p>
        <p class="hint">{{ t('diagnostic.noFilesYet') }}</p>
        <div class="form-actions">
          <button type="button" class="action-button" :disabled="busy" @click="start">
            {{ busy ? t('diagnostic.working') : t('diagnostic.start') }}
          </button>
        </div>
      </template>
      <template v-else-if="session.status === 'COLLECTING_EVIDENCE'">
        <h2>{{ t('diagnostic.intakeTitle') }}</h2>
        <p>{{ t('diagnostic.intakeDescription') }}</p>
        <p class="hint">{{ t('diagnostic.noFilesYet') }}</p>
        <div class="form-actions">
          <button type="button" class="action-button" :disabled="busy" @click="continueWithoutRecording">
            {{ busy ? t('diagnostic.working') : t('diagnostic.continue') }}
          </button>
        </div>
      </template>
      <template v-else>
        <h2>{{ t('diagnostic.readyTitle') }}</h2>
        <p>{{ t('diagnostic.readyDescription') }}</p>
        <p class="hint">{{ t('diagnostic.noAssessment') }}</p>
      </template>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p><RouterLink to="/onboarding">{{ t('diagnostic.back') }}</RouterLink></p>
    </div>
  </section>
</template>
