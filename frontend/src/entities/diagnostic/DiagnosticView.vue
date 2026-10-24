<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { apiFailureKey, asApiFailure, isHttp, type ApiFailure } from '../../shared/api-failure';
import { onBeforeRouteLeave } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { diagnosticService } from './diagnostic.service';
import ExercisePreview from './ExercisePreview.vue';
import { closeMetronome } from '../metronome/metronome-ui';
import SubmissionPanel from '../attempt/SubmissionPanel.vue';
import DiagnosticEvidencePanel from './DiagnosticEvidencePanel.vue';
import { intakeNavigationAction, permitIntakeNavigation, shouldWarnBeforeUnload } from './intake-navigation';
import type { DiagnosticSession, DiagnosticTask, Exercise, DiagnosticRecommendation } from '../../shared/model/diagnostic.model';

const { t } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
// Match auth.ts secure-mode rule without introducing a runtime dependency in diagnostic views.
const showPrototypeNotice = import.meta.env?.DEV === true && import.meta.env?.VITE_GUITAR_MENTOR_AUTH_REQUIRED !== 'true';
const learnerStore = useLearnerStore();
const session = ref<DiagnosticSession | null>(null);
const loading = ref(true);
const busy = ref(false);
const intakeBusy = ref(false);
const intakeDraft = ref(false);
const submissionBusy = ref(false);
const submissionDraft = ref(false);
function onSubmissionDraft(value: { busy: boolean; dirty: boolean }): void {
  submissionBusy.value = value.busy;
  submissionDraft.value = value.dirty;
}
const error = ref('');
const writeError = ref<ApiFailure | null>(null);
const restoreError = ref<ApiFailure | null>(null);
const catalogError = ref<ApiFailure | null>(null);
const catalogMissing = ref(false);
const catalogFailure = ref(false);
const failedRestoreRead = ref(false);
const displayError = computed(() => error.value || (restoreError.value ? t(apiFailureKey(restoreError.value)) : writeError.value ? t(apiFailureKey(writeError.value)) : ''));
const catalogErrorMessage = computed(() => catalogMissing.value ? t('diagnostic.catalogApiMissing') : catalogError.value ? t(apiFailureKey(catalogError.value)) : '');
let restoreGeneration = 0;
let catalogGeneration = 0;
let recommendationGeneration = 0;
let active = true;

function onIntakeState(value: { busy: boolean; dirty: boolean }): void {
  const wasBusy = intakeBusy.value;
  intakeBusy.value = value.busy;
  intakeDraft.value = value.dirty;
  // A failed navigation while recording is no longer a current error after
  // the microphone stops or the POST finishes. Preserve unrelated failures.
  if (wasBusy && !value.busy && error.value === t('evidence.waitBeforeLeave')) {
    error.value = '';
  }
}
const catalogBusy = ref(false);
const exercises = ref<Exercise[]>([]);
// Legacy entries without contentKind remain learning repertoire.
const probes = computed(() => exercises.value.filter(e => e.contentKind === 'DIAGNOSTIC_PROBE'));
const repertoire = computed(() => exercises.value.filter(e => e.contentKind !== 'DIAGNOSTIC_PROBE'));
const recommendation = ref<DiagnosticRecommendation | null>(null);
const recommendationBusy = ref(false);
const recommendationError = ref('');
const task = ref<DiagnosticTask | null>(null);
const taskLoading = ref(false);
const taskFailure = ref<'missing' | 'network' | null>(null);
let taskGeneration = 0;

/** Applies to Back, header links, router.back() and all other SPA route changes. */
function allowRouteLeave(): boolean {
  if (session.value?.status === 'TASK_ASSIGNED') {
    if (submissionBusy.value) {
      error.value = t('evidence.waitBeforeLeave');
      return false;
    }
    return !submissionDraft.value || window.confirm(t('evidence.confirmDiscard'));
  }
  if (session.value?.status !== 'COLLECTING_EVIDENCE') return true;
  const action = intakeNavigationAction(busy.value, intakeBusy.value, intakeDraft.value);
  if (action === 'BLOCK') {
    error.value = t('evidence.waitBeforeLeave');
    return false;
  }
  return permitIntakeNavigation(busy.value, intakeBusy.value, intakeDraft.value,
    () => window.confirm(t('evidence.confirmDiscard')));
}

onBeforeRouteLeave(() => allowRouteLeave());

/** SPA leave guards do not run for F5, tab close, or browser navigation away. */
function onBrowserUnload(event: BeforeUnloadEvent): void {
  const intakeWarning = shouldWarnBeforeUnload(session.value?.status === 'COLLECTING_EVIDENCE',
    busy.value || intakeBusy.value, intakeDraft.value);
  const assignmentWarning = session.value?.status === 'TASK_ASSIGNED' &&
    (submissionBusy.value || submissionDraft.value);
  if (!intakeWarning && !assignmentWarning) return;
  event.preventDefault();
  event.returnValue = '';
}
onMounted(() => window.addEventListener('beforeunload', onBrowserUnload));
onBeforeUnmount(() => { active = false; ++restoreGeneration; ++catalogGeneration; ++recommendationGeneration; ++taskGeneration; window.removeEventListener('beforeunload', onBrowserUnload); });

async function loadTask(learnerId: string, diagnosticId: string): Promise<void> {
  const seq = ++taskGeneration;
  const current = () => active && seq === taskGeneration &&
    learnerStore.learner?.id === learnerId && session.value?.id === diagnosticId &&
    session.value.status === 'TASK_ASSIGNED';
  taskLoading.value = true;
  taskFailure.value = null;
  task.value = null;
  try {
    const requestedTask = await diagnosticService.currentTask(learnerId, diagnosticId);
    if (!current()) return;
    if (!requestedTask) taskFailure.value = 'missing';
    else task.value = requestedTask;
  } catch (cause) {
    if (current() && !(cause instanceof Error && cause.name === 'AbortError'))
      taskFailure.value = isHttp(cause, 404) ? 'missing' : 'network';
  } finally {
    if (current()) taskLoading.value = false;
  }
}

function retryAssignedTask(): void {
  const id = learnerStore.learner?.id;
  const diagnosticId = session.value?.id;
  if (!id || !diagnosticId || session.value?.status !== 'TASK_ASSIGNED' || taskLoading.value) return;
  void loadTask(id, diagnosticId);
}

async function loadCatalog(): Promise<void> {
  const id = learnerStore.learner?.id;
  const sessionId = session.value?.id;
  const seq = ++catalogGeneration;
  const current = () => active && seq === catalogGeneration
    && learnerStore.learner?.id === id && session.value?.id === sessionId;
  catalogBusy.value = true;
  catalogFailure.value = false;
  error.value = '';
  catalogError.value = null;
  catalogMissing.value = false;
  try {
    const result = await diagnosticService.listExercises();
    if (current()) exercises.value = result;
  } catch (cause) {
    if (current() && !(cause instanceof Error && cause.name === 'AbortError')) {
      catalogFailure.value = true;
      catalogMissing.value = isHttp(cause, 404);
      catalogError.value = catalogMissing.value ? null : asApiFailure(cause);
    }
  } finally {
    if (current()) catalogBusy.value = false;
  }
  if (current()) await loadRecommendations();
}

async function loadRecommendations(): Promise<void> {
  const id = learnerStore.learner?.id;
  const sessionId = session.value?.id;
  if (!id || !sessionId || session.value?.status !== 'READY_FOR_TASK') return;
  const seq = ++recommendationGeneration;
  const current = () => active && seq === recommendationGeneration
    && learnerStore.learner?.id === id && session.value?.id === sessionId;
  recommendationBusy.value = true;
  recommendationError.value = '';
  try {
    const result = await diagnosticService.recommendations(id, sessionId);
    if (current()) recommendation.value = result;
  } catch (cause) {
    if (current() && !(cause instanceof Error && cause.name === 'AbortError')) {
      recommendation.value = null;
      recommendationError.value = 'failed';
    }
  } finally {
    if (current()) recommendationBusy.value = false;
  }
}
const instrumentCode = 'ACOUSTIC_GUITAR';

function storageKey(learnerId: string): string {
  return 'guitar-mentor.diagnostic.' + learnerId + '.' + instrumentCode;
}

async function restoreSession(): Promise<void> {
  const seq = ++restoreGeneration;
  loading.value = true;
  restoreError.value = null;
  failedRestoreRead.value = false;
  const selectedLearner = learnerStore.learner?.id ?? window.localStorage.getItem('guitar-mentor.learnerId');
  try {
    if (!learnerStore.learner) await learnerStore.restore();
    if (learnerStore.error) throw learnerStore.error;
    const id = learnerStore.learner?.id;
    if (!id || id !== selectedLearner) return;
    const key = storageKey(id);
    const stored = window.localStorage.getItem(key);
    if (!stored) return;
    let loaded: DiagnosticSession;
    try {
      loaded = await diagnosticService.get(id, stored);
    } catch (cause) {
      if (isHttp(cause, 404)) {
        if (active && seq === restoreGeneration && learnerStore.learner?.id === id
          && window.localStorage.getItem(key) === stored) {
          window.localStorage.removeItem(key);
          session.value = null;
        }
        return;
      }
      throw cause;
    }
    if (!active || seq !== restoreGeneration || learnerStore.learner?.id !== id
      || window.localStorage.getItem(key) !== stored) return;
    session.value = loaded;
    if (loaded.status !== 'TASK_ASSIGNED') { ++taskGeneration; task.value = null; taskFailure.value = null; taskLoading.value = false; }
    writeError.value = null;
    if (loaded.status === 'TASK_ASSIGNED') await loadTask(id, loaded.id);
    if (loaded.status === 'READY_FOR_TASK') await loadCatalog();
  } catch (cause) {
    if (active && seq === restoreGeneration && !(cause instanceof Error && cause.name === 'AbortError')) {
      restoreError.value = asApiFailure(cause);
      failedRestoreRead.value = true;
    }
  } finally {
    if (active && seq === restoreGeneration) loading.value = false;
  }
}
onMounted(() => { void restoreSession(); });

async function start(): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || busy.value || loading.value || failedRestoreRead.value) return;
  if (window.localStorage.getItem(storageKey(id))) {
    // A local selection exists but no session is shown. Recover it visibly; never silently ignore Start.
    await restoreSession();
    if (!session.value && !failedRestoreRead.value) error.value = t('diagnostic.startRecoveryHint');
    return;
  }
  busy.value = true;
  error.value = '';
  writeError.value = null;
  failedRestoreRead.value = false;
  try {
    session.value = await diagnosticService.start(id, instrumentCode);
    window.localStorage.setItem(storageKey(id), session.value.id);
  } catch (cause) {
    writeError.value = asApiFailure(cause);
  } finally {
    busy.value = false;
  }
}

async function continueWithoutRecording(): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || !session.value || failedRestoreRead.value) return;
  // The same confirmation policy also protects the browser Back and header links.
  if (!permitIntakeNavigation(busy.value, intakeBusy.value, intakeDraft.value,
    () => window.confirm(t('evidence.confirmDiscard')))) return;
  busy.value = true;
  error.value = '';
  writeError.value = null;
  failedRestoreRead.value = false;
  try {
    session.value = await diagnosticService.continueWithoutRecording(id, session.value.id);
    await loadCatalog();
  } catch (cause) {
    writeError.value = asApiFailure(cause);
  } finally {
    busy.value = false;
  }
}
async function assign(exerciseCode: string): Promise<void> {
  const id = learnerStore.learner?.id;
  if (!id || !session.value || busy.value || failedRestoreRead.value) return;
  busy.value = true;
  error.value = '';
  writeError.value = null;
  failedRestoreRead.value = false;
  try {
    task.value = await diagnosticService.assignTask(id, session.value.id, exerciseCode);
    closeMetronome(); // Assignment replaces the selected exercise; no carry-over audio.
    session.value = await diagnosticService.get(id, session.value.id);
  } catch (cause) {
    writeError.value = asApiFailure(cause);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="profile-page diagnostic-workspace" :class="{ 'diagnostic-workspace--light': lightMinimal }">
    <div v-if="!lightMinimal || session?.status !== 'TASK_ASSIGNED' || !task" class="intro">
      <span class="eyebrow">{{ t("diagnostic.workspace.lesson") }}</span>
      <h1>{{ session?.status === 'TASK_ASSIGNED' ? t('diagnostic.workspace.lesson') : t('diagnostic.title') }}</h1>
      <p>{{ session?.status === 'TASK_ASSIGNED' ? t('diagnostic.workspace.lessonSubtitle') : t('diagnostic.subtitle') }}</p>
    </div>
    <div v-if="showPrototypeNotice" class="notice" role="note">{{ t('form.notice') }}</div>
    <div v-if="loading" class="panel">{{ t('diagnostic.loading') }}</div>
    <div v-else-if="!learnerStore.learner && failedRestoreRead && restoreError" class="panel" role="alert">
      <p>{{ t(apiFailureKey(restoreError)) }}</p>
      <button type="button" class="action-button" @click="restoreSession">{{ t("home.retry") }}</button>
    </div>
    <div v-else-if="!learnerStore.learner" class="panel">
      <p>{{ t('diagnostic.needProfile') }}</p>
      <RouterLink to="/profile">{{ t('app.profile') }}</RouterLink>
    </div>
    <div v-else class="panel" :class="{ 'lesson-panel--light': lightMinimal && session?.status === 'TASK_ASSIGNED', 'studio-panel': lightMinimal && session?.status === 'TASK_ASSIGNED' && task }">
      <details v-if="session && (!lightMinimal || session.status !== 'TASK_ASSIGNED')" class="session-details">
        <summary>{{ t('diagnostic.workspace.sessionDetails') }}</summary>
        <p class="hint">{{ t('diagnostic.session') }}: {{ session.id }}</p>
      </details>
      <DiagnosticEvidencePanel v-if="session && learnerStore.learner && (!lightMinimal || session.status !== 'TASK_ASSIGNED')"
        :learner-id="learnerStore.learner.id" :session-id="session.id"
        :allow-upload="session.status === 'COLLECTING_EVIDENCE'"
        @intake-state="onIntakeState" />
      <template v-if="!session && !failedRestoreRead">
        <h2>{{ t('diagnostic.startTitle') }}</h2>
        <p>{{ t('diagnostic.startDescription') }}</p>
        <div class="form-actions">
          <button type="button" class="action-button" :disabled="busy" @click="start">
            {{ busy ? t('diagnostic.working') : t('diagnostic.start') }}
          </button>
        </div>
      </template>
      <template v-else-if="session?.status === 'COLLECTING_EVIDENCE'">
        <div class="form-actions">
          <button type="button" class="action-button" :disabled="busy || intakeBusy" @click="continueWithoutRecording">
            {{ busy ? t('diagnostic.working') : t('diagnostic.continue') }}
          </button>
        </div>
      </template>

      <template v-else-if="session?.status === 'READY_FOR_TASK'">
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
        <h3>{{ t('diagnostic.probesHeading') }}</h3>
        <p class="hint">{{ t('diagnostic.probesHint') }}</p>
        <p v-if="!catalogBusy && !catalogFailure && probes.length === 0" class="hint" role="status">
          {{ t('diagnostic.noProbes') }}
        </p>
        <article v-for="exercise in probes" :key="exercise.code + '@' + exercise.version"
          class="exercise-card">
          <h4>{{ exercise.title }}</h4>
          <p class="hint">{{ t('diagnostic.probeDuration') }}: {{ exercise.durationTargetSeconds ?? '—' }} {{ t('diagnostic.seconds') }}</p>
          <ExercisePreview :exercise="exercise" />
          <button type="button" class="action-button" :disabled="busy"
            @click="assign(exercise.code)">{{ t('diagnostic.chooseProbe') }}</button>
        </article>
        <h3>{{ t('diagnostic.repertoireHeading') }}</h3>
        <p class="hint">{{ t('diagnostic.repertoireHint') }}</p>

        <p v-if="catalogBusy" class="hint">{{ t('diagnostic.catalogLoading') }}</p>
        <p v-else-if="!catalogFailure && repertoire.length === 0" class="hint">{{ t('diagnostic.noExercises') }}</p>
        <p v-if="catalogFailure" class="error" role="alert">{{ catalogErrorMessage }}</p>
        <button v-if="catalogFailure" type="button" class="action-button secondary-button" :disabled="catalogBusy" @click="loadCatalog">
          {{ t('diagnostic.retryCatalog') }}
        </button>
        <article v-for="exercise in repertoire" :key="exercise.code" class="exercise-card">
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
      <template v-else-if="session?.status === 'TASK_ASSIGNED' && !task">
        <div class="assigned-task-recovery" :aria-busy="taskLoading">
          <p v-if="taskLoading" role="status">{{ t('diagnostic.assignedTaskLoading') }}</p>
          <template v-else>
            <p role="alert">{{ t(taskFailure === 'missing' ? 'diagnostic.assignedTaskMissing' : 'diagnostic.assignedTaskUnavailable') }}</p>
            <button type="button" class="action-button secondary-button" @click="retryAssignedTask">
              {{ t('home.retry') }}
            </button>
          </template>
        </div>
      </template>
      <template v-else-if="session?.status === 'TASK_ASSIGNED' && task && lightMinimal">
        <div class="studio-titlebar">
          <div class="studio-titlecopy">
            <p class="studio-overline">{{ t('diagnostic.studio.overline') }}</p>
            <h2>{{ t('diagnostic.studio.currentTitle') }}</h2>
            <p>{{ t('diagnostic.studio.subtitle') }}</p>
          </div>
          <div class="studio-assignment" :aria-label="t('diagnostic.studio.assigned')">
            <div class="studio-assignment-icon" aria-hidden="true">♫</div>
            <div class="studio-assignment-description">
              <strong>{{ task.exercise.title }}</strong>
              <span>{{ task.exercise.code }} · v{{ task.exercise.version }}</span>
              <small>{{ task.exercise.bpm }} BPM · {{ task.exercise.meter }} · {{ task.exercise.measures }} {{ t('diagnostic.bars') }}</small>
            </div>
            <div class="studio-assignment-actions">
              <div v-if="task.exercise.previewAudioUrl" class="studio-audio-example">
                <span class="studio-audio-label">{{ t('diagnostic.listenExample') }}</span>
                <audio :key="task.exercise.code + '@' + task.exercise.version"
                  :src="task.exercise.previewAudioUrl" preload="none" controls
                  :aria-label="t('diagnostic.listenExample')" />
              </div>
              <span v-else class="studio-audio-unavailable">{{ t('diagnostic.metadata.audioUnavailable') }}</span>
            </div>
            <span class="studio-assignment-status">{{ t('diagnostic.studio.inProgress') }}</span>
          </div>
        </div>
        <div class="studio-columns">
          <section class="studio-score" :aria-label="t('diagnostic.workspace.materials')">
            <ExercisePreview :exercise="task.exercise" assigned />
          </section>
          <aside class="studio-sidebar" :aria-label="t('diagnostic.workspace.recording')">
            <SubmissionPanel v-if="learnerStore.learner"
              :learner-id="learnerStore.learner.id"
              :session-id="session.id" :task-id="task.id" @draft-state="onSubmissionDraft" />
          </aside>
        </div>
        <details class="studio-extra">
          <summary>{{ t('diagnostic.studio.details') }}</summary>
          <p>{{ task.exercise.learningGoal?.trim() || task.exercise.description || t('diagnostic.metadata.unknown') }}</p>
          <p>{{ task.exercise.license }}</p>
        </details>
      </template>
      <template v-else-if="session?.status === 'TASK_ASSIGNED' && task && !lightMinimal">
        <div class="exercise-meta">{{ task.exercise.code }} · v{{ task.exercise.version }}</div>
        <h2>{{ task.exercise.title }}</h2>
        <p>{{ task.exercise.learningGoal?.trim() || task.exercise.description || t('diagnostic.metadata.unknown') }}</p>
        <div class="exercise-stats">
          <span>{{ task.exercise.bpm }} BPM<template v-if="task.exercise.tempoUnit"> · {{ task.exercise.tempoUnit }}</template></span>
          <span>{{ task.exercise.meter }}</span>
          <span>{{ task.exercise.measures }} {{ t('diagnostic.bars') }}</span>
          <span>{{ task.exercise.license }}</span>
          <span v-if="task.exercise.recommendedPracticeMinutes != null">{{ t('diagnostic.practiceDuration', { minutes: task.exercise.recommendedPracticeMinutes }) }}</span>
        </div>
        <div class="lesson-workspace" :class="{ 'lesson-workspace--light': lightMinimal }">
          <section class="lesson-materials" :aria-label="t('diagnostic.workspace.materials')">
            <h3>{{ t('diagnostic.workspace.materials') }}</h3>
            <p class="hint">{{ t('diagnostic.draftWarning') }}</p>
            <ExercisePreview :exercise="task.exercise" assigned />
          </section>
          <section class="lesson-submission" :aria-label="t('diagnostic.workspace.recording')">
            <SubmissionPanel v-if="learnerStore.learner"
              :learner-id="learnerStore.learner.id"
              :session-id="session.id" :task-id="task.id" @draft-state="onSubmissionDraft" />
          </section>
        </div>
      </template>
      <p v-if="displayError" role="alert" class="error">{{ displayError }}</p>
      <button v-if="failedRestoreRead && !busy && !intakeBusy && !submissionBusy && !intakeDraft && !submissionDraft" type="button" class="text-button" @click="restoreSession">{{ t("home.retry") }}</button>
      <p v-if="!lightMinimal || session?.status !== 'TASK_ASSIGNED'" class="studio-back"><RouterLink to="/onboarding">{{ t('diagnostic.back') }}</RouterLink></p>
    </div>
  </section>
</template>

<style scoped>
.lesson-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; align-items: start; margin-top: 24px; }
.lesson-materials, .lesson-submission { min-width: 0; width: 100%; }
.lesson-materials :deep(.exercise-preview), .lesson-materials :deep(.score-frame-wrapper) { width: 100%; min-width: 0; box-sizing: border-box; }
.lesson-materials { border: 1px solid #dce5e2; border-radius: 14px; padding: 20px; background: #f8fbfa; }
.lesson-materials h3 { margin-top: 0; }
.lesson-submission :deep(.homework-panel) { margin-top: 0; }
.session-details { margin-bottom: 18px; font-size: 14px; color: #566777; }
.session-details p { overflow-wrap: anywhere; }
@media(max-width: 800px) { .lesson-workspace { grid-template-columns: 1fr; gap: 16px; } }
@media(max-width: 400px) { .lesson-materials { padding: 14px; } }
.assigned-task-recovery {padding:24px;border:1px solid #dce5e4;border-radius:12px;background:#f8fbfa}
.assigned-task-recovery p {margin:0 0 14px}
/* #150 opt-in lesson layout; existing diagnostic states keep their controls. */
.diagnostic-workspace--light {
  width:min(100% - 48px,1560px);box-sizing:border-box;margin-inline:auto;
  padding:32px 0 72px;min-width:0;
}
.diagnostic-workspace--light .intro {max-width:100%;margin-inline:0}
.diagnostic-workspace--light .intro .eyebrow {display:none}
.diagnostic-workspace--light .lesson-panel--light > .session-details {margin:0 0 14px}
.diagnostic-workspace--light .lesson-panel--light > .session-details + :deep(.evidence-details) {margin-bottom:12px}

.diagnostic-workspace--light .intro {margin-bottom:24px}
.diagnostic-workspace--light .intro h1 {font-size:clamp(28px,3.2vw,44px);letter-spacing:-.035em}
.diagnostic-workspace--light .lesson-panel--light {background:transparent;border:0;box-shadow:none;padding:0}
.lesson-workspace--light {grid-template-columns:minmax(0,1fr) 320px;gap:24px;align-items:start}
.lesson-workspace--light .lesson-materials {background:var(--gm-surface,#fff);border-color:var(--gm-border,#dce5e4);padding:24px;border-radius:16px;min-width:0}
.lesson-workspace--light .lesson-submission {min-width:0}
.lesson-workspace--light .lesson-submission :deep(.homework-panel) {
  background:var(--gm-surface,#fff);border:1px solid var(--gm-border,#dce5e4);
  border-radius:16px;padding:20px;box-shadow:none;overflow-wrap:anywhere;
}
.lesson-workspace--light .lesson-submission :deep(.homework-form) {
  display:flex;flex-direction:column;gap:9px;min-width:0;
}
.lesson-workspace--light .lesson-submission :deep(input[type="file"]) {
  max-width:100%;width:100%;min-width:0;box-sizing:border-box;
  font-size:13px;padding:10px;border:1px solid var(--gm-border,#dce5e4);
  background:#f8fbfa;border-radius:10px;
}
.lesson-workspace--light .lesson-submission :deep(input[type="file"]::file-selector-button) {
  border:0;border-radius:8px;background:#e5f0eb;color:#205f57;
  padding:9px 12px;cursor:pointer;margin-right:8px;font:inherit;
}
.lesson-workspace--light .lesson-submission :deep(textarea) {
  width:100%;box-sizing:border-box;min-width:0;border-radius:10px;
}
.lesson-workspace--light .lesson-submission :deep(.homework-heading) {margin-bottom:16px}
.lesson-workspace--light .lesson-submission :deep(.homework-heading h3) {font-size:17px;margin:0 0 6px}
.lesson-workspace--light .lesson-submission :deep(.hint) {line-height:1.45}
.lesson-workspace--light .lesson-submission :deep(.form-actions .action-button) {width:100%;min-height:44px}
.lesson-workspace--light .lesson-submission :deep(.recorded-preview audio) {max-width:100%;width:100%}

.lesson-workspace--light :deep(.score-canvas-frame) {max-width:100%}
.lesson-workspace--light .lesson-materials h3 {color:var(--gm-text,#162331)}
.lesson-workspace--light :deep(.exercise-preview) {min-width:0}
@media(max-width:1099px) {
  .lesson-workspace--light {grid-template-columns:minmax(0,1fr)}
}
@media(max-width:600px) {
  .diagnostic-workspace--light {width:calc(100% - 32px);padding:24px 0 60px}
  .lesson-workspace--light {gap:16px}
  .lesson-workspace--light .lesson-materials {padding:14px}
}


/* FT-016-03 visual rebuild: one studio shell, not nested legacy panels. */
.profile-page.diagnostic-workspace--light {
  max-width:none;width:min(calc(100% - 64px),1560px);margin:0 auto;
  padding:36px 0 70px;box-sizing:border-box;
}
.diagnostic-workspace--light .studio-panel {
  padding:0!important;border:0!important;background:transparent!important;
  box-shadow:none!important;max-width:none;overflow:visible;
}
.studio-titlebar {display:grid;grid-template-columns:minmax(280px,.44fr) minmax(0,1fr);gap:32px;align-items:center;margin:0 0 24px}
.studio-overline {font-size:12px;font-weight:750;letter-spacing:.14em;text-transform:uppercase;color:#64776f;margin:0 0 8px}
.studio-titlecopy h2 {font-size:clamp(30px,3.5vw,48px);letter-spacing:-.04em;line-height:1.1;margin:0 0 8px;color:#162331}
.studio-titlecopy p:last-child {color:#5b6a71;font-size:15px;margin:0}
.studio-assignment {display:flex;align-items:center;gap:20px;min-width:0;background:#fff;border:1px solid #e1e8e5;border-radius:16px;padding:20px;box-shadow:0 6px 24px #1623310a}
.studio-assignment-icon {flex:none;width:90px;height:88px;border-radius:12px;background:linear-gradient(135deg,#d9ede2,#f2f7f2);color:#28776d;font-size:48px;display:grid;place-items:center}
.studio-assignment-description {display:flex;flex-direction:column;gap:6px;min-width:0}
.studio-assignment-description strong {font-size:20px;line-height:1.25;overflow-wrap:anywhere}
.studio-assignment-description span {color:#56666a;font-size:13px;overflow-wrap:anywhere}
.studio-assignment-description small {font-size:13px;color:#28776d}
.studio-assignment-status {margin-left:auto;flex:none;padding:6px 10px;color:#226851;background:#e6f3e9;border-radius:999px;font-size:12px;font-weight:650}
.studio-columns {display:grid;grid-template-columns:minmax(0,1fr) minmax(340px,500px);align-items:start;gap:16px}
.studio-score,.studio-sidebar {min-width:0}
.studio-score {padding:18px;background:#fff;border:1px solid #e0e7e4;border-radius:16px;box-shadow:0 5px 24px #16233108}
.studio-sidebar :deep(.homework-panel) {margin:0;padding:18px;border:1px solid #e0e7e4;border-radius:16px;background:#fff;box-shadow:0 5px 24px #16233108}
.studio-sidebar :deep(.homework-heading p) {font-size:13px}
.studio-sidebar :deep(.homework-form) {gap:12px}
.studio-sidebar :deep(.recording-primary) {border:0;background:transparent;padding:0}
.studio-sidebar :deep(.recording-primary h4) {display:none}
.studio-sidebar :deep(.microphone-recorder) {border:0;padding:0;box-shadow:none;background:transparent}
.studio-sidebar :deep(.microphone-recorder > strong) {display:none}
.studio-sidebar :deep(.microphone-recorder > .hint) {font-size:12px;line-height:1.35;margin:6px 0}
.studio-sidebar :deep(.microphone-controls) {margin-top:12px}
.studio-sidebar :deep(.microphone-controls button) {border-radius:10px;min-height:44px}
.studio-sidebar :deep(.recording-file-option) {margin:0 0 8px}
.studio-sidebar :deep(.homework-history) {margin-top:16px}
.studio-sidebar :deep(.homework-panel textarea) {width:100%;max-width:100%}
.studio-extra {margin:18px 0 0;color:#53635e;font-size:14px}
.studio-extra summary {cursor:pointer;min-height:44px}
@media(max-width:1080px) {
  .studio-titlebar {grid-template-columns:1fr;gap:18px}
  .studio-columns {grid-template-columns:minmax(0,1fr)}
  .studio-sidebar {max-width:none}
}
@media(max-width:620px) {
  .profile-page.diagnostic-workspace--light {width:calc(100% - 32px);padding:20px 0 50px}
  .studio-assignment {padding:14px;gap:12px;flex-wrap:wrap}
  .studio-assignment-icon {width:60px;height:60px;font-size:32px}
  .studio-assignment-status {margin-left:0}
  .studio-score {padding:10px}
}


/* #150 studio acceptance: real inline audio in the assigned exercise banner. */
.studio-assignment {gap:16px}
.studio-assignment-icon {width:76px;height:76px;font-size:40px}
.studio-assignment-description {flex:1 1 220px}
.studio-assignment-actions {flex:0 1 310px;min-width:190px;margin-left:auto;display:flex;align-items:center;gap:12px}
.studio-audio-example {flex:1;min-width:0;border:1px solid #e3e9e6;background:#f9fbfa;border-radius:11px;padding:7px 10px}
.studio-audio-label {display:block;font-size:11px;color:#4b6059;margin-bottom:2px}
.studio-audio-example audio {width:100%;min-width:0;height:34px;display:block}
.studio-audio-unavailable {font-size:12px;color:#687975}
.studio-assignment-status {margin-left:0}
@media(max-width:1280px) {
 .studio-assignment {flex-wrap:wrap}
 .studio-assignment-actions {flex:1 1 300px;margin-left:0}
}
@media(max-width:620px) {
 .studio-assignment-actions {flex:1 1 100%;min-width:0;flex-wrap:wrap}
}


/* Reviewed assignment hero: details/audio share space; status anchored in corner. */
.studio-assignment {position:relative;gap:14px;padding:18px 22px;min-height:116px;box-sizing:border-box}
.studio-assignment-description {flex:1 1 225px;max-width:none;padding-right:8px}
.studio-assignment-actions {flex:1 1 270px;min-width:220px;margin:0 100px 0 0}
.studio-audio-example {width:100%;min-width:0;padding:9px 12px}
.studio-audio-example audio {height:42px;width:100%}
.studio-audio-label {font-size:12px;font-weight:600}
.studio-assignment-status {position:absolute;right:16px;top:14px;font-size:11px;white-space:nowrap}
@media(max-width:1280px){
 .studio-assignment {flex-wrap:wrap}
 .studio-assignment-actions {flex:1 1 270px;margin:0 90px 0 0}
}
@media(max-width:740px){
 .studio-assignment {padding-top:42px}
 .studio-assignment-description {flex:1 1 160px}
 .studio-assignment-actions {flex:1 1 100%;margin:0}
 .studio-assignment-status {top:10px;right:12px}
}
</style>
