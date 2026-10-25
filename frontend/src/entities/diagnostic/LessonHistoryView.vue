<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { auth, secureUi } from '../../core/auth/auth';
import { diagnosticService } from './diagnostic.service';
import { apiFailureKey, asApiFailure } from '../../shared/api-failure';
import ExercisePreview from './ExercisePreview.vue';
import { attemptService, type SubmissionAttempt } from '../attempt/attempt.service';
import { learningService, type DecisionView, type SkillEvidenceView } from '../attempt/learning.service';
import type { DiagnosticTask, LessonHistoryItem } from '../../shared/model/diagnostic.model';

const { t } = useI18n(), route = useRoute(), store = useLearnerStore();
const items = ref<LessonHistoryItem[]>([]), nextCursor = ref<string | null>(null);
const detail = ref<DiagnosticTask | null>(null);
const attempts = ref<SubmissionAttempt[]>([]);
const decisions = ref<DecisionView[]>([]);
const evidence = ref<SkillEvidenceView[]>([]);
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
      const task = await diagnosticService.currentTask(learner, session.id);
      if (valid()) detail.value = task;
      if (task && valid()) {
        const [oldAttempts, oldDecisions, oldEvidence] = await Promise.all([
          attemptService.list(learner,session.id,task.id),
          learningService.decisions(learner,session.id,task.id),
          learningService.evidence(learner,session.id,task.id),
        ]);
        if (valid()) {
          attempts.value=oldAttempts; decisions.value=oldDecisions.items;
          evidence.value=oldEvidence.items;
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
watch(requestedId, () => { items.value=[]; detail.value=null; attempts.value=[]; decisions.value=[]; evidence.value=[]; void load(); });
watch(() => auth.actor, () => {
  ++generation; items.value=[]; detail.value=null; attempts.value=[]; decisions.value=[]; evidence.value=[];
  if (auth.actor || !secureUi) void load();
});
onMounted(() => void load());
onBeforeUnmount(() => { ++generation; });
</script>
<template>
  <main class="profile-page">
    <h1>{{ t('lessonNext.history') }}</h1>
    <p v-if="loading" role="status">{{ t('diagnostic.loading') }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="error" type="button" @click="load()">{{ t('home.retry') }}</button>
    <template v-if="requestedId && detail">
      <p>{{ t('lessonNext.readOnly') }}</p>
      <h2>{{ detail.exercise.title }}</h2>
      <ExercisePreview :exercise="detail.exercise" assigned />
      <section class="panel">
        <h3>{{ t('lessonNext.attempts') }}</h3>
        <ul><li v-for="attempt in attempts" :key="attempt.id">
          №{{ attempt.number }} · {{ attempt.status }} · {{ attempt.comment || '—' }}
          <span v-if="attempt.mediaState === 'DELETED'">{{ t('lessonNext.mediaExpired') }}</span>
        </li></ul>
        <h3>{{ t('lessonNext.decisions') }}</h3>
        <ul><li v-for="decision in decisions" :key="decision.decisionId">
          {{ decision.action }} · {{ decision.resultingState }} · {{ decision.reasonText || '—' }}
        </li></ul>
        <h3>{{ t('lessonNext.evidence') }}</h3>
        <ul><li v-for="entry in evidence" :key="entry.assessmentId + entry.criterionCode">
          {{ entry.criterionCode }} · {{ entry.status }} · {{ entry.outcome || '—' }}
        </li></ul>
      </section>
    </template>
    <template v-if="!requestedId">
      <p v-if="!loading && !items.length">{{ t('lessonNext.emptyHistory') }}</p>
      <ol>
        <li v-for="item in items" :key="item.taskId">
          <RouterLink :to="'/lessons/' + item.sessionId">{{ item.title }}</RouterLink>
          · v{{ item.exerciseVersion }} · {{ item.taskState || t('diagnostic.studio.statusUnavailable') }}
        </li>
      </ol>
      <button v-if="nextCursor" type="button" :disabled="loading"
        @click="load(nextCursor)">{{ t('lessonNext.more') }}</button>
    </template>
    <RouterLink to="/diagnostics">{{ t('lessonNext.current') }}</RouterLink>
  </main>
</template>
