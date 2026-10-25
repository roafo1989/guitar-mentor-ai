<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { auth, secureUi } from '../../core/auth/auth';
import { diagnosticService } from './diagnostic.service';
import { apiFailureKey, asApiFailure } from '../../shared/api-failure';
import ExercisePreview from './ExercisePreview.vue';
import { attemptService, type SubmissionAttempt } from '../attempt/attempt.service';
import { learningService, type DecisionView, type SkillEvidenceView, type TaskLearningView } from '../attempt/learning.service';
import type { DiagnosticTask, LessonHistoryItem } from '../../shared/model/diagnostic.model';

const { t, locale } = useI18n(), route = useRoute(), router = useRouter(), store = useLearnerStore();
function statusLabel(value: string | null | undefined): string {
  if (value === 'COMPLETED_BY_LEARNER') return locale.value === 'ru' ? 'Завершено учеником' : 'Completed by learner';
  if (value === 'COMPLETED_PASSED') return locale.value === 'ru' ? 'Завершено успешно' : 'Completed — passed';
  if (value === 'ACTIVE') return locale.value === 'ru' ? 'В процессе' : 'In progress';
  return t('diagnostic.studio.statusUnavailable');
}
function assignedDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(locale.value === 'ru' ? 'ru-RU' : 'en-US').format(date);
}
const items = ref<LessonHistoryItem[]>([]), nextCursor = ref<string | null>(null);
const detail = ref<DiagnosticTask | null>(null);
const attempts = ref<SubmissionAttempt[]>([]);
const decisions = ref<DecisionView[]>([]);
const evidence = ref<SkillEvidenceView[]>([]);
const decisionCursor = ref<string | null>(null);
const evidenceCursor = ref<string | null>(null);
const learning = ref<TaskLearningView | null>(null);
const loading = ref(true), error = ref('');
let generation = 0;
const requestedId = computed(() => typeof route.params.sessionId === 'string' ? route.params.sessionId : null);
async function load(cursor?: string): Promise<void> {
  const seq = ++generation, actor = auth.actor;
  const valid = () => seq === generation && (!secureUi || auth.actor === actor);
  loading.value = true; error.value = '';
  try {
    if (!store.learner || (secureUi && store.learner.id !== actor?.learnerId)) await store.restore();
    const learner = secureUi ? actor?.learnerId : store.learner?.id;
    if (!learner || !valid()) return;
    if (requestedId.value) {
      const session = await diagnosticService.get(learner, requestedId.value);
      if (!valid()) return;
      // An active/current session must use the full lesson workspace, never the archive-only detail.
      // ARCHIVED sessions remain here as read-only history; no selected key is mutated.
      if (session.status !== 'ARCHIVED') {
        await router.replace('/diagnostics');
        return;
      }
      const task = await diagnosticService.currentTask(learner, session.id);
      if (valid()) detail.value = task;
      if (task && valid()) {
        const [oldAttempts, oldDecisions, oldEvidence, oldState] = await Promise.all([
          attemptService.list(learner,session.id,task.id),
          learningService.decisions(learner,session.id,task.id),
          learningService.evidence(learner,session.id,task.id),
          learningService.state(learner,session.id,task.id),
        ]);
        if (valid()) {
          attempts.value=oldAttempts; decisions.value=oldDecisions.items;
          evidence.value=oldEvidence.items; learning.value=oldState;
          decisionCursor.value=oldDecisions.nextCursor; evidenceCursor.value=oldEvidence.nextCursor;
        }
      }
    } else {
      const page = await diagnosticService.history(learner, cursor);
      if (valid()) {
        items.value = cursor ? [...items.value, ...page.items] : page.items;
        nextCursor.value = page.nextCursor;
      }
    }
  } catch (cause) {
    if (valid()) error.value = t(apiFailureKey(asApiFailure(cause)));
  } finally { if (valid()) loading.value = false; }
}
async function moreDecisions() {
  const learner = secureUi ? auth.actor?.learnerId : store.learner?.id;
  const task = detail.value, cursor = decisionCursor.value;
  const key = generation;
  if (!learner || !task || !cursor) return;
  try {
    const page = await learningService.decisions(learner,task.sessionId,task.id,cursor);
    if (key === generation) { decisions.value.push(...page.items); decisionCursor.value=page.nextCursor; }
  } catch { if (key === generation) error.value=t('apiErrors.http'); }
}
async function moreEvidence() {
  const learner = secureUi ? auth.actor?.learnerId : store.learner?.id;
  const task = detail.value, cursor = evidenceCursor.value;
  const key = generation;
  if (!learner || !task || !cursor) return;
  try {
    const page = await learningService.evidence(learner,task.sessionId,task.id,cursor);
    if (key === generation) { evidence.value.push(...page.items); evidenceCursor.value=page.nextCursor; }
  } catch { if (key === generation) error.value=t('apiErrors.http'); }
}
watch(requestedId, () => { items.value=[]; detail.value=null; attempts.value=[]; decisions.value=[]; evidence.value=[]; learning.value=null; void load(); });
watch(() => auth.actor, () => {
  ++generation; items.value=[]; detail.value=null; attempts.value=[]; decisions.value=[]; evidence.value=[]; learning.value=null;
  if (auth.actor || !secureUi) void load();
});
onMounted(() => void load());
onBeforeUnmount(() => { ++generation; });
</script>
<template>
  <main class="profile-page lesson-history-page">
    <header class="lesson-history-header">
      <h1>{{ t('lessonNext.history') }}</h1>
    </header>
    <p v-if="loading" role="status">{{ t('diagnostic.loading') }}</p>
    <p v-if="error" role="alert" class="lesson-history-error">{{ error }}</p>
    <button v-if="error" class="action-button secondary-button" type="button" @click="load()">{{ t('home.retry') }}</button>
    <template v-if="requestedId && detail">
      <div class="lesson-history-meta" role="status">{{ t('lessonNext.readOnly') }}</div>
      <h2>{{ detail.exercise.title }}</h2>
      <ExercisePreview :exercise="detail.exercise" assigned />
      <section class="panel lesson-history-detail">
        <h3>{{ t('lessonNext.attempts') }}</h3>
        <ul><li v-for="attempt in attempts" :key="attempt.id">
          №{{ attempt.number }} · {{ attempt.status }} · {{ attempt.comment || '—' }}
          <span v-if="attempt.mediaState === 'DELETED'">{{ t('lessonNext.mediaExpired') }}</span>
        </li></ul>
        <p v-if="learning?.nextLesson">
          <RouterLink :to="'/lessons/' + learning.nextLesson.sessionId">{{ t('lessonNext.successor') }}</RouterLink>
        </p>
        <h3>{{ t('lessonNext.decisions') }}</h3>
        <ul><li v-for="decision in decisions" :key="decision.decisionId">
          {{ decision.action }} · {{ statusLabel(decision.resultingState) }} · {{ decision.reasonText || '—' }}
        </li></ul>
        <button v-if="decisionCursor" class="action-button secondary-button" type="button" @click="moreDecisions">{{ t('lessonNext.more') }}</button>
        <h3>{{ t('lessonNext.evidence') }}</h3>
        <ul><li v-for="entry in evidence" :key="entry.assessmentId + entry.criterionCode">
          {{ entry.criterionCode }} · {{ entry.status }} · {{ entry.outcome || '—' }}
          <p v-if="entry.reasonCode">{{ entry.reasonCode }}</p>
          <p v-if="entry.evidenceText">{{ entry.evidenceText }}</p>
        </li></ul>
        <button v-if="evidenceCursor" class="action-button secondary-button" type="button" @click="moreEvidence">{{ t('lessonNext.more') }}</button>
      </section>
    </template>
    <template v-if="!requestedId">
      <p v-if="!loading && !items.length" class="lesson-history-empty">{{ t('lessonNext.emptyHistory') }}</p>
      <ol class="lesson-history-list">
        <li v-for="item in items" :key="item.taskId" class="lesson-history-card">
          <div class="lesson-history-card-heading">
            <RouterLink :to="item.isCurrent ? '/diagnostics' : '/lessons/' + item.sessionId" class="lesson-history-name">{{ item.title }}</RouterLink>
            <span v-if="item.isCurrent" class="lesson-history-current">{{ t('lessonNext.currentMarker') }}</span>
          </div>
          <div class="lesson-history-details">
            <span>{{ statusLabel(item.taskState) }}</span>
            <span>{{ assignedDate(item.assignedAt) }}</span>
            <span>v{{ item.exerciseVersion }}</span>
          </div>
        </li>
      </ol>
      <button v-if="nextCursor" class="action-button secondary-button" type="button" :disabled="loading"
        @click="load(nextCursor)">{{ t('lessonNext.more') }}</button>
    </template>
    <nav class="lesson-history-nav">
      <RouterLink to="/diagnostics">{{ t('lessonNext.current') }}</RouterLink>
    </nav>
  </main>
</template>

<style scoped>
.lesson-history-page { box-sizing:border-box; width:min(100%, 1080px); max-width:1080px; min-width:0; margin:0 auto; padding:clamp(1.25rem,3vw,2.5rem); display:flex; flex-direction:column; gap:1.25rem; }
.lesson-history-header h1 { margin:0; font-size:clamp(1.65rem,2.8vw,2.3rem); line-height:1.2; letter-spacing:-.025em; }
.lesson-history-list { display:grid; list-style:none; padding:0; margin:0; gap:.85rem; }
.lesson-history-card { display:flex; flex-direction:column; gap:.75rem; min-width:0; padding:1.25rem 1.5rem; background:var(--surface,#fff); border:1px solid var(--border-color,#e1e7e4); border-radius:1rem; box-shadow:0 8px 25px rgba(20,43,35,.035); overflow-wrap:anywhere; }
.lesson-history-card-heading { display:flex; flex-wrap:wrap; align-items:center; gap:.6rem 1rem; }
.lesson-history-name { font-size:1.1rem; line-height:1.4; font-weight:700; color:inherit; text-decoration:none; }
.lesson-history-name:hover { text-decoration:underline; text-underline-offset:.2em; }
.lesson-history-current { display:inline-flex; border-radius:999px; background:#e9f5f0; color:#216c60; padding:.3rem .75rem; font-size:.8rem; font-weight:600; }
.lesson-history-details { display:flex; flex-wrap:wrap; gap:.45rem 1.25rem; color:var(--text-secondary,#5b6b69); font-size:.9rem; }
.lesson-history-meta { padding:.65rem .85rem; border-radius:.65rem; background:var(--surface,#f3f7f5); font-weight:600; }
.lesson-history-detail { min-width:0; overflow-wrap:anywhere; }
.lesson-history-detail li { margin-block:.5rem; }
.lesson-history-detail button { margin-block:.5rem; }
.lesson-history-nav { display:flex; flex-wrap:wrap; gap:1rem; padding-top:.25rem; }
.lesson-history-nav a { display:inline-flex; align-items:center; min-height:2.5rem; text-underline-offset:.2em; }
.lesson-history-page a:focus-visible, .lesson-history-page button:focus-visible { outline:3px solid var(--accent,#24786e); outline-offset:3px; }
.lesson-history-error { overflow-wrap:anywhere; }
@media(max-width:600px) { .lesson-history-page { padding:1rem; } .lesson-history-card { padding:1rem; } }
</style>
