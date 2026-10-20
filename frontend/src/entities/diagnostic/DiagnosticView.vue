<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { diagnosticService } from './diagnostic.service';
import ExercisePreview from './ExercisePreview.vue';
import SubmissionPanel from '../attempt/SubmissionPanel.vue';
import DiagnosticEvidencePanel from './DiagnosticEvidencePanel.vue';
import type { DiagnosticSession, DiagnosticTask, Exercise, DiagnosticRecommendation } from '../../shared/model/diagnostic.model';

const { t } = useI18n();
const learnerStore = useLearnerStore();
const session = ref<DiagnosticSession | null>(null);
const loading = ref(true);
const busy = ref(false);
const intakeBusy = ref(false);
const intakeDraft = ref(false);

function onIntakeState(value: { busy: boolean; dirty: boolean }): void {
  intakeBusy.value = value.busy;
  intakeDraft.value = value.dirty;
}
const error = ref('');
const catalogBusy = ref(false);
const exercises = ref<Exercise[]>([]);
const recommendation = ref<DiagnosticRecommendation | null>(null);
const recommendationBusy = ref(false);
const recommendationError = ref('');
const task = ref<DiagnosticTask | null>(null);

async function loadTask(learnerId: string, diagnosticId: string): Promise<void> {
  task.value = await diagnosticService.currentTask(learnerId, diagnosticId);
}

async function loadCatalog(): Promise<void> {
  catalogBusy.value = true;
  error.value = '';
  try {
    exercises.value = await diagnosticService.listExercises();
  } catch (cause) {
    exercises.value = [];
    error.value = cause instanceof Error && cause.message.startsWith('HTTP_404')
      ? t('diagnostic.catalogApiMissing') : showError(cause);
  } finally {
    catalogBusy.value = false;
  }
  await loadRecommendations();
}

async function loadRecommendations(): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || !session.value || session.value.status !== 'READY_FOR_TASK') return;
  recommendationBusy.value = true;
  recommendationError.value = '';
  try {
    recommendation.value = await diagnosticService.recommendations(id, session.value.id);
  } catch (cause) {
    recommendation.value = null;
    recommendationError.value = showError(cause);
  } finally {
    recommendationBusy.value = false;
  }
}
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
      // Only the session GET 404 invalidates its stored ID.
      // 404 from the exercise catalog must NOT delete the session selection.
      if (cause instanceof Error && cause.message.startsWith('HTTP_404')) {
        window.localStorage.removeItem(storageKey(id));
        session.value = null;
        return;
      }
      throw cause;
    }
    if (session.value.status === 'TASK_ASSIGNED') await loadTask(id, session.value.id);
    if (session.value.status === 'READY_FOR_TASK') await loadCatalog();
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
  if (!id || !session.value || busy.value || intakeBusy.value) return;
  // Do not silently discard a local recording or upload form when leaving intake.
  if (intakeDraft.value && !window.confirm(t('evidence.confirmDiscard'))) return;
  busy.value = true;
  error.value = '';
  try {
    session.value = await diagnosticService.continueWithoutRecording(id, session.value.id);
    await loadCatalog();
  } catch (cause) {
    error.value = showError(cause);
  } finally {
    busy.value = false;
  }
}
async function assign(exerciseCode: string): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || !session.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    task.value = await diagnosticService.assignTask(id, session.value.id, exerciseCode);
    session.value = await diagnosticService.get(id, session.value.id);
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
      <DiagnosticEvidencePanel v-if="session && learnerStore.learner"
        :learner-id="learnerStore.learner.id" :session-id="session.id"
        :allow-upload="session.status === 'COLLECTING_EVIDENCE'"
        @intake-state="onIntakeState" />
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
          <button type="button" class="action-button" :disabled="busy || intakeBusy" @click="continueWithoutRecording">
            {{ busy ? t('diagnostic.working') : t('diagnostic.continue') }}
          </button>
        </div>
      </template>

      <template v-else-if="session.status === 'READY_FOR_TASK'">
        <h2>{{ t('diagnostic.readyTitle') }}</h2>
        <p>{{ t('diagnostic.chooseStudy') }}</p>
        <section class="recommendation-panel" aria-labelledby="recommended-heading">
          <h3 id="recommended-heading">{{ t('diagnostic.recommendationTitle') }}</h3>
          <p v-if="recommendationBusy" role="status">{{ t('diagnostic.recommendationLoading') }}</p>
          <div v-else-if="recommendationError" role="alert">
            <p>{{ t('diagnostic.recommendationUnavailable') }}</p>
            <button class="action-button secondary-button" type="button"
              :disabled="recommendationBusy" @click="loadRecommendations">
              {{ t('diagnostic.retryRecommendations') }}
            </button>
          </div>
          <p v-else-if="recommendation?.status === 'NO_MATCH'" role="status">
            {{ t('diagnostic.noRecommendation.' + recommendation.reasonCode) }}
            {{ t('diagnostic.manualChoice') }}
          </p>
          <article v-for="item in recommendation?.items ?? []" :key="item.exerciseCode"
            class="exercise-card">
            <h4>{{ item.title }}</h4>
            <p>{{ t('diagnostic.practiceDuration', { minutes: item.recommendedPracticeMinutes }) }}</p>
            <p>{{ item.skillCodes.join(', ') }}</p>
            <p class="hint">{{ item.reasonCodes.map(code => t('diagnostic.recommendationReason.' + code)).join(' · ') }}</p>
            <button type="button" class="action-button" :disabled="busy"
              @click="assign(item.exerciseCode)">{{ t('diagnostic.assignRecommended') }}</button>
          </article>
        </section>
        <h3>{{ t('diagnostic.catalogAll') }}</h3>
        <p v-if="catalogBusy" class="hint">{{ t('diagnostic.catalogLoading') }}</p>
        <p v-else-if="!error && exercises.length === 0" class="hint">{{ t('diagnostic.noExercises') }}</p>
        <button v-if="error" type="button" class="action-button secondary-button" :disabled="catalogBusy" @click="loadCatalog">
          {{ t('diagnostic.retryCatalog') }}
        </button>
        <article v-for="exercise in exercises" :key="exercise.code" class="exercise-card">
          <div class="exercise-meta">{{ exercise.code }} · v{{ exercise.version }} · {{ exercise.bpm }} BPM · {{ exercise.measures }} {{ t('diagnostic.bars') }}</div>
          <h3>{{ exercise.title }}</h3>
          <p>{{ exercise.description }}</p>
          <p class="hint">{{ t('diagnostic.draftWarning') }}</p>
          <ExercisePreview :exercise="exercise" />
          <button type="button" class="action-button" :disabled="busy" @click="assign(exercise.code)">
            {{ t('diagnostic.assign') }}
          </button>
        </article>
      </template>
      <template v-else-if="session.status === 'TASK_ASSIGNED' && task">
        <div class="exercise-meta">{{ task.exercise.code }} · v{{ task.exercise.version }}</div>
        <h2>{{ task.exercise.title }}</h2>
        <p>{{ task.exercise.description }}</p>
        <div class="exercise-stats">
          <span>{{ task.exercise.bpm }} BPM</span>
          <span>{{ task.exercise.meter }}</span>
          <span>{{ task.exercise.measures }} {{ t('diagnostic.bars') }}</span>
          <span>{{ task.exercise.license }}</span>
        </div>
        <h3>{{ t('diagnostic.taskInstructions') }}</h3>
        <p>{{ t('diagnostic.practiceTips') }}</p>
        <p class="hint">{{ t('diagnostic.draftWarning') }}</p>
        <ExercisePreview :exercise="task.exercise" assigned />
        <SubmissionPanel v-if="learnerStore.learner"
          :learner-id="learnerStore.learner.id"
          :session-id="session.id" :task-id="task.id" />
        <p class="hint">{{ t('diagnostic.noAssessment') }}</p>
      </template>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p><RouterLink to="/onboarding">{{ t('diagnostic.back') }}</RouterLink></p>
    </div>
  </section>
</template>
