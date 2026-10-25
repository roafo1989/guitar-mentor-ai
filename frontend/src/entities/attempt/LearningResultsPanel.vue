<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { LearningHttpError, learningService } from './learning.service';
import type { AssessmentView, DecisionCommand, DecisionView, SkillEvidenceView, TaskLearningView } from './learning.service';
import { attemptService } from './attempt.service';
import { isTaskState, safeTaskStateKey, safeRecommendationKey, safeSourceKey, safeCriterionStatusKey, safeOutcomeKey, localDate, validSelectedAssessment } from './learning-presentation';
import type { SubmissionAttempt } from './attempt.service';

const props = defineProps<{
  learnerId: string; sessionId: string; taskId: string; attempts: SubmissionAttempt[];
  draftBusy: boolean; selectedId: string; latestId: string;
  loadAttemptDetail?: (id: string) => Promise<SubmissionAttempt>;
}>();
const emit = defineEmits<{ 'decision-busy': [value: boolean]; 'terminal-state': [value: boolean]; 'selected-id': [id: string]; 'latest-id': [id: string]; 'learning-state': [value: { learnerId: string; sessionId: string; taskId: string; taskState: TaskLearningView['taskState'] | null; fresh: boolean }] }>();
const { t, locale } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const state = ref<TaskLearningView | null>(null);
const assessments = ref<AssessmentView[]>([]);
const decisions = ref<DecisionView[]>([]);
const evidence = ref<SkillEvidenceView[]>([]);
const evidenceCursor = ref<string | null>(null);
const evidenceError = ref('');
const evidenceLoading = ref(false);
const decisionHistoryError = ref('');
const decisionHistoryLoading = ref(false);
const selected = ref('');
const pinnedDetail = ref<SubmissionAttempt | null>(null);
const detailError = ref('');
const detailFresh = ref(false);
const assessmentError = ref('');
const detailBusy = ref(false);
function formatDate(value: string | null | undefined) { return localDate(value, locale.value); }
function learningStateEvent() {
  emit('learning-state', { learnerId: props.learnerId, sessionId: props.sessionId, taskId: props.taskId,
    taskState: stateFresh.value && isTaskState(state.value?.taskState) ? state.value!.taskState : null,
    fresh: stateFresh.value && isTaskState(state.value?.taskState) });
}
let detailSequence = 0;
watch(selected, id => emit('selected-id', id));
const reason = ref('');
const loading = ref(true);
const sending = ref(false);
watch(sending, value => emit('decision-busy', value), { immediate: true });
const error = ref('');
const notice = ref('');
const assessmentCursor = ref<string | null>(null);
const decisionCursor = ref<string | null>(null);
let generation = 0;
const stateFresh = ref(false);
const assessmentFresh = ref(false);
const pending = ref<DecisionCommand | null>(null);
let decisionSequence = 0;

const currentAttempt = computed(() => (pinnedDetail.value?.id === selected.value ? pinnedDetail.value : null) ?? props.attempts.find(a => a.id === selected.value));
const selectorAttempts = computed(() => {
  const entries = [...props.attempts];
  if (pinnedDetail.value?.id === selected.value) entries.push(pinnedDetail.value);
  return [...new Map(entries.map(a => [a.id, a])).values()].sort((a, b) => b.number - a.number);
});
const latestAssessment = computed(() => assessments.value.find(a => validSelectedAssessment(a, selected.value)) ?? null);
const terminal = computed(() => !!state.value && isTaskState(state.value.taskState) && state.value.taskState !== 'ACTIVE');
watch([state, stateFresh], learningStateEvent, { immediate: true });
watch(terminal, value => emit('terminal-state', value), { immediate: true });
const canDecide = computed(() => !!state.value && state.value.taskState === 'ACTIVE' &&
  Array.isArray(state.value.availableActions) && !!currentAttempt.value &&
  detailFresh.value && !detailError.value && stateFresh.value && assessmentFresh.value && !pending.value &&
  !props.draftBusy && !sending.value && !loading.value);
const missingAssessment = computed(() => assessmentFresh.value && !latestAssessment.value);
const technicalStatus = computed(() => currentAttempt.value?.status);
function message(e: unknown): string {
  if (!(e instanceof LearningHttpError)) return t('learning.errors.UNEXPECTED_RESPONSE');
  const key = 'learning.errors.' + e.code;
  return t(key) === key ? t('learning.errors.UNEXPECTED_RESPONSE') : t(key);
}
async function loadDetail(seq: number) {
  const id = selected.value;
  const serial = ++detailSequence;
  detailFresh.value = false; detailError.value = ''; detailBusy.value = !!id;
  // Retain the previously displayed detail while revalidating it.
  if (!id) { pinnedDetail.value = null; detailBusy.value = false; return; }
  // A page DTO is display-only; a decision needs a successful authoritative detail GET.
  try {
    const detail = await (props.loadAttemptDetail
      ? props.loadAttemptDetail(id)
      : attemptService.detail(props.learnerId, props.sessionId, props.taskId, id));
    if (seq === generation && serial === detailSequence && selected.value === id) {
      pinnedDetail.value = detail; detailFresh.value = true;
    }
  } catch {
    if (seq === generation && serial === detailSequence) {
      detailFresh.value = false;
      detailError.value = t('learning.attemptUnavailable');
    }
  } finally {
    if (seq === generation && serial === detailSequence) detailBusy.value = false;
  }
}
async function refresh() {
  const seq = ++generation;
  loading.value = true; stateFresh.value = false; assessmentFresh.value = false; error.value = '';
  learningStateEvent();
  try {
    const next = await learningService.state(props.learnerId, props.sessionId, props.taskId);
    if (seq !== generation) return;
    state.value = next; stateFresh.value = isTaskState(next.taskState);
    if (!stateFresh.value) error.value = t('learning.unavailable');
    learningStateEvent();
    emit('latest-id', next.latestAttemptId ?? '');
    void loadDecisionHistory(seq);
    void loadEvidence(seq);
    if (!selected.value) selected.value = next.latestAttemptId ?? props.attempts[0]?.id ?? '';
    await Promise.all([loadDetail(seq), loadAssessments(seq)]);
  } catch (cause) {
    if (seq !== generation) return;
    error.value = message(cause);
  } finally {
    if (seq === generation) loading.value = false;
  }
}
async function loadDecisionHistory(seq = generation) {
  decisionHistoryLoading.value = true;
  decisionHistoryError.value = '';
  try {
    const result = await learningService.decisions(props.learnerId, props.sessionId, props.taskId);
    if (seq !== generation) return;
    decisions.value = result.items;
    decisionCursor.value = result.nextCursor;
  } catch (cause) {
    if (seq === generation) decisionHistoryError.value = message(cause);
  } finally {
    if (seq === generation) decisionHistoryLoading.value = false;
  }
}
async function loadEvidence(seq = generation) {
  evidenceLoading.value = true;
  evidenceError.value = '';
  try {
    const result = await learningService.evidence(props.learnerId, props.sessionId, props.taskId);
    if (seq !== generation) return;
    evidence.value = result.items;
    evidenceCursor.value = result.nextCursor;
  } catch (cause) {
    if (seq !== generation) return;
    evidenceError.value = message(cause);
  } finally {
    if (seq === generation) evidenceLoading.value = false;
  }
}
async function loadAssessments(seq = generation) {
  const attempt = selected.value;
  assessments.value = []; assessmentCursor.value = null; assessmentFresh.value = false; assessmentError.value = '';
  if (!attempt) return;
  try {
    const result = await learningService.assessments(props.learnerId, props.sessionId, props.taskId, attempt);
    if (seq !== generation || attempt !== selected.value) return;
    assessments.value = result.items.filter(a => a.attemptId === attempt); assessmentCursor.value = result.nextCursor;
    assessmentFresh.value = true;
  } catch (cause) {
    if (seq === generation && attempt === selected.value) {
      assessmentError.value = message(cause);
      assessmentFresh.value = false;
    }
  }
}
async function chooseAttempt(id: string) {
  if (sending.value || pending.value) return;
  selected.value = id;
  const seq = ++generation;
  loading.value = true; error.value = '';
  try { await Promise.all([loadDetail(seq), loadAssessments(seq)]); }
  finally { if (seq === generation) loading.value = false; }
}
async function moreAssessments() {
  const cursor = assessmentCursor.value;
  if (!cursor || loading.value) return;
  const seq = generation, attempt = selected.value;
  loading.value = true;
  try {
    const result = await learningService.assessments(props.learnerId, props.sessionId, props.taskId, attempt, cursor);
    if (seq === generation && attempt === selected.value) {
      assessments.value.push(...result.items.filter(a => a.attemptId === attempt)); assessmentCursor.value = result.nextCursor;
    }
  } catch (cause) { if (seq === generation) error.value = message(cause); }
  finally { if (seq === generation) loading.value = false; }
}
async function moreEvidence() {
  const cursor = evidenceCursor.value;
  if (!cursor || evidenceLoading.value) return;
  const seq = generation;
  evidenceLoading.value = true;
  try {
    const result = await learningService.evidence(props.learnerId, props.sessionId, props.taskId, cursor);
    if (seq === generation) {
      evidence.value.push(...result.items); evidenceCursor.value = result.nextCursor;
    }
  } catch (cause) { if (seq === generation) evidenceError.value = message(cause); }
  finally { if (seq === generation) evidenceLoading.value = false; }
}
async function moreDecisions() {
  const cursor = decisionCursor.value;
  if (!cursor || decisionHistoryLoading.value) return;
  const seq = generation;
  decisionHistoryLoading.value = true;
  try {
    const result = await learningService.decisions(props.learnerId, props.sessionId, props.taskId, cursor);
    if (seq === generation) {
      decisions.value.push(...result.items); decisionCursor.value = result.nextCursor;
    }
  } catch (cause) { if (seq === generation) decisionHistoryError.value = message(cause); }
  finally { if (seq === generation) decisionHistoryLoading.value = false; }
}
async function decide(action: 'CONTINUE_PRACTICING' | 'COMPLETE') {
  if (!canDecide.value || !state.value || !selected.value ||
      !state.value.availableActions.includes(action)) return;
  if (action === 'COMPLETE' && !window.confirm(t('learning.confirmComplete'))) return;
  const command: DecisionCommand = {
    requestId: crypto.randomUUID(), attemptId: selected.value, action,
    expectedVersion: state.value.version, baseAssessmentId: latestAssessment.value?.id ?? null,
    reason: reason.value.trim() || null,
  };
  pending.value = command;
  await sendCommand(command);
}
async function retry() {
  if (!pending.value || sending.value || props.draftBusy) return;
  await sendCommand(pending.value);
}
async function sendCommand(command: DecisionCommand) {
  const seq = generation;
  const operation = ++decisionSequence;
  const context = JSON.stringify([props.learnerId, props.sessionId, props.taskId]);
  const currentDecision = () => operation === decisionSequence && context === JSON.stringify([props.learnerId, props.sessionId, props.taskId]);
  sending.value = true; error.value = ''; notice.value = '';
  try {
    await learningService.decide(props.learnerId, props.sessionId, props.taskId, command);
    if (seq !== generation || !currentDecision()) return;
    pending.value = null;
    await refresh();
    if (currentDecision() && stateFresh.value) notice.value = t('learning.saved');
  } catch (cause) {
    if (seq !== generation || !currentDecision()) return;
    if (cause instanceof LearningHttpError && cause.status === 409) {
      pending.value = null;
      await refresh();
      if (currentDecision()) error.value = t('learning.conflict');
    } else {
      error.value = message(cause);
    }
  } finally { if (currentDecision()) sending.value = false; }
}
watch(() => [props.learnerId, props.sessionId, props.taskId], () => {
  ++generation; ++detailSequence; ++decisionSequence; selected.value = ''; pinnedDetail.value = null;
  detailFresh.value = false; detailError.value = ''; pending.value = null; state.value = null;
  assessments.value = []; decisions.value = []; evidence.value = [];
  evidenceCursor.value = null; evidenceError.value = '';
  decisionCursor.value = null; decisionHistoryError.value = ''; stateFresh.value = false;
  assessmentFresh.value = false; assessmentError.value = ''; reason.value = ''; sending.value = false;
  learningStateEvent(); void refresh();
});
watch(() => props.attempts, () => {
  if (selected.value && !loading.value && !sending.value && !pending.value) {
    detailFresh.value = false;
    void refresh();
  }
});
watch(() => {
  const item = props.attempts.find(a => a.id === selected.value);
  return item ? item.id + ':' + item.status + ':' + item.assessmentAvailable : '';
}, (next, previous) => {
  if (next && previous && next !== previous && !loading.value && !sending.value && !pending.value) void refresh();
});

onMounted(() => { void refresh(); });
onBeforeUnmount(() => { ++generation; ++detailSequence; ++decisionSequence; pending.value = null; stateFresh.value = false; learningStateEvent(); });
</script>

<template>
  <section class="homework-history" aria-labelledby="learning-title">
    <div :class="{ 'studio-learning-heading': lightMinimal }">
      <h3 id="learning-title">{{ t('learning.title') }}</h3>
      <button type="button" class="action-button secondary-button" :class="{ 'studio-refresh-button': lightMinimal }"
        :disabled="loading || sending" @click="refresh">
        <svg v-if="lightMinimal" width="16" height="16" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 9a7 7 0 0 1 12-2l2 5M4 12l2 5a7 7 0 0 0 12-2"/>
        </svg>{{ t('learning.refresh') }}
      </button>
    </div>
    <p v-if="loading" role="status">{{ t('learning.loading') }}</p>
    <p v-if="error" :class="lightMinimal ? 'studio-learning-info' : 'error'" role="alert">{{ error }}</p>
    <p v-if="state && !stateFresh" class="hint" role="status">{{ t('learning.stale') }}</p>
    <template v-if="state">
      <p role="status">{{ t(safeTaskStateKey(state.taskState)) }}</p>
      <p v-if="state.latestAttemptId" class="hint">{{ t('learning.latestAttempt') }}: {{ state.latestAttemptId === selected ? t('learning.selectedIsLatest') : t('learning.differentSelected') }}</p>
      <p v-if="terminal && state.nextStepAvailability === 'NOT_IMPLEMENTED'" class="hint">{{ t('learning.nextUnavailable') }}</p>
      <RouterLink v-if="terminal && stateFresh" to="/lessons/new" class="action-button">{{ t('lessonNext.choose') }}</RouterLink>
      <div class="learning-attempt-picker">
        <label for="learning-attempt">{{ t('learning.chooseAttempt') }}</label>
        <select id="learning-attempt" :value="selected" :disabled="loading || sending" @change="chooseAttempt(($event.target as HTMLSelectElement).value)">
          <option v-if="selected && !selectorAttempts.some(attempt => attempt.id === selected)" :value="selected">{{ t('learning.attemptUnavailable') }}</option>
          <option v-for="attempt in selectorAttempts" :key="attempt.id" :value="attempt.id">
            {{ t('attempt.attempt') }} №{{ attempt.number }}
          </option>
        </select>
      </div>
      <p v-if="detailError" class="error" role="alert">{{ detailError }} <button type="button" @click="loadDetail(generation)">{{ t("learning.retry") }}</button></p>
      <p v-if="!selected && !currentAttempt" class="hint">{{ t('learning.noAttempts') }}</p>
      <p v-else-if="detailBusy && !detailFresh" role="status">{{ t('learning.loading') }}</p>
      <p v-else-if="!currentAttempt" class="hint">{{ t('learning.attemptUnavailable') }}</p>
      <template v-else>
        <p v-if="technicalStatus === 'FAILED'" class="hint">{{ t('learning.technicalFailure') }}</p>
        <p v-else-if="technicalStatus === 'UPLOADED' || technicalStatus === 'PROCESSING'" class="hint">{{ t('learning.processing') }}</p>
        <p v-else-if="technicalStatus !== 'PROCESSED'" role="status" class="hint">{{ t('learning.unavailable') }}</p>
        <p v-else-if="assessmentError" role="alert" class="error">{{ assessmentError }} <button type="button" @click="loadAssessments()">{{ t('learning.refresh') }}</button></p>
        <p v-else-if="!assessmentFresh" role="status" class="hint">{{ t('learning.loading') }}</p>
        <p v-else-if="missingAssessment" class="hint">{{ t('learning.noAssessment') }}</p>
        <template v-if="technicalStatus === 'PROCESSED' && assessmentFresh && latestAssessment">
          <h4>{{ t('learning.feedback') }}</h4>
          <p>{{ t(safeRecommendationKey(latestAssessment.recommendation)) }}</p>
          <details><summary>{{ t('learning.assessmentDetails') }}</summary>
            <p class="hint">{{ t(safeSourceKey(latestAssessment.sourceKind)) }} · {{ latestAssessment.methodId }} v{{ latestAssessment.methodVersion }}</p>
            <p class="hint">{{ formatDate(latestAssessment.createdAt) }}</p>
          </details>
          <ul>
            <li v-for="criterion in latestAssessment.criteria" :key="criterion.criterionCode">
              <strong>{{ criterion.label }}</strong> — {{ t(safeCriterionStatusKey(criterion.status)) }} · {{ t(safeOutcomeKey(criterion.outcome)) }}
              <p>{{ criterion.observableResult }}</p>
              <p v-if="criterion.evidenceText">{{ criterion.evidenceText }}</p>
              <p class="hint">{{ t('learning.confirmedLevel') }}: {{ criterion.confirmedSkillLevel ?? t('learning.unknown') }}</p>
            </li>
          </ul>
          <p v-for="limitation in latestAssessment.limitations" :key="limitation" class="hint">{{ limitation }}</p>
        </template>
        <details v-if="assessments.length > 1">
          <summary>{{ t('learning.olderAssessments') }}</summary>
          <article v-for="assessment in assessments.slice(1)" :key="assessment.id">
            <p>{{ t(safeRecommendationKey(assessment.recommendation)) }} · {{ formatDate(assessment.createdAt) }}</p>
            <p v-for="criterion in assessment.criteria" :key="criterion.criterionCode">{{ criterion.label }}: {{ t(safeOutcomeKey(criterion.outcome)) }}</p>
          </article>
        </details>
        <button v-if="assessmentCursor" class="action-button secondary-button" type="button" :disabled="loading" @click="moreAssessments">{{ t('learning.more') }}</button>
        <template v-if="canDecide">
          <label for="learning-reason">{{ t('learning.reason') }}</label>
          <textarea id="learning-reason" v-model="reason" maxlength="2000" rows="2" />
          <div class="form-actions">
            <button class="action-button secondary-button" type="button" :disabled="!state.availableActions.includes('CONTINUE_PRACTICING')" @click="decide('CONTINUE_PRACTICING')">{{ t('learning.continue') }}</button>
            <button class="action-button" type="button" :disabled="!state.availableActions.includes('COMPLETE')" @click="decide('COMPLETE')">{{ t('learning.complete') }}</button>
          </div>
        </template>
      </template>
      <button v-if="pending && !sending" class="action-button secondary-button" type="button" :disabled="draftBusy" @click="retry">{{ t('learning.retry') }}</button>
      <p v-if="notice" role="status">{{ notice }}</p>
      <div v-if="evidenceError || decisionHistoryError" role="alert" class="error">
        {{ evidenceError || decisionHistoryError }}
      </div>
      <details class="learning-history-details">
        <summary>{{ t('learning.historyDetails') }}</summary>
        <h4>{{ t('learning.skillEvidence') }}</h4>
      <p v-if="evidenceLoading" role="status">{{ t('learning.loading') }}</p>
      <div v-if="evidenceError">
        <p>{{ evidenceError }}</p>
        <button type="button" class="action-button secondary-button" :disabled="evidenceLoading" @click="loadEvidence()">{{ t('learning.refresh') }}</button>
      </div>
      <p class="hint">{{ t('learning.skillEvidenceHelp') }}</p>
      <p v-if="!evidence.length && !evidenceLoading && !evidenceError" class="hint">{{ t('learning.noSkillEvidence') }}</p>
      <details v-for="entry in evidence" :key="entry.assessmentId + entry.criterionCode">
        <summary>{{ entry.skillCode }} · {{ t(safeCriterionStatusKey(entry.status)) }} · {{ t(safeOutcomeKey(entry.outcome)) }}</summary>
        <p v-if="entry.evidenceText">{{ entry.evidenceText }}</p>
        <p>{{ t('learning.confirmedLevel') }}: {{ entry.confirmedSkillLevel ?? t('learning.unknown') }}</p>
        <p class="hint">{{ t(safeSourceKey(entry.sourceKind)) }} · {{ entry.methodId }} v{{ entry.methodVersion }} · {{ formatDate(entry.createdAt) }}</p>
      </details>
      <button v-if="evidenceCursor" class="action-button secondary-button" type="button" :disabled="evidenceLoading" @click="moreEvidence">{{ t('learning.more') }}</button>
      <h4>{{ t('learning.decisionHistory') }}</h4>
      <p v-if="decisionHistoryLoading" role="status">{{ t('learning.loading') }}</p>
      <div v-if="decisionHistoryError">
        <p>{{ decisionHistoryError }}</p>
        <button type="button" class="action-button secondary-button" :disabled="decisionHistoryLoading" @click="loadDecisionHistory()">{{ t('learning.refresh') }}</button>
      </div>
      <p v-if="!decisions.length && !decisionHistoryLoading && !decisionHistoryError" class="hint">{{ t('learning.noDecisions') }}</p>
      <details v-for="decision in decisions" :key="decision.decisionId">
        <summary>{{ t(safeTaskStateKey(decision.resultingState)) }} · {{ formatDate(decision.recordedAt) }}</summary>
        <p>{{ decision.reasonText || decision.reasonCode }}</p>
      </details>
      <button v-if="decisionCursor" type="button" class="action-button secondary-button" :disabled="decisionHistoryLoading" @click="moreDecisions">{{ t('learning.more') }}</button>
      </details>
    </template>
  </section>
</template>

<style scoped>
.learning-attempt-picker {display:flex;flex-direction:column;align-items:stretch;gap:8px;margin:16px 0;min-width:0}
.learning-attempt-picker label {display:block;margin:0;font-size:13px;font-weight:600;color:#425b52}
.learning-attempt-picker select {display:block;box-sizing:border-box;width:100%;min-width:0;min-height:44px;padding:10px 36px 10px 12px;border:1px solid #dce5e0;border-radius:10px;background-color:#fff;color:inherit;font:inherit}
.learning-attempt-picker select:focus-visible {outline:3px solid #28776d;outline-offset:2px}
.learning-attempt-picker select:disabled {opacity:.65;cursor:not-allowed}
.studio-learning-heading {display:flex;align-items:center;justify-content:space-between;gap:8px;margin:0 0 12px}
.studio-learning-heading h3 {font-size:17px;line-height:1.35;margin:0}
.studio-learning-heading .studio-refresh-button {min-height:36px;display:inline-flex;align-items:center;gap:5px;padding:6px 9px;border:1px solid #dbe5df;background:#f7faf8;border-radius:8px;color:#426b58;white-space:nowrap;font-size:12px;min-width:0}
.studio-learning-info {padding:12px;border-radius:10px;color:#56665e;background:#f3f7f4;border:1px solid #e4ece5;font-size:13px;line-height:1.5}
@media(max-width:360px){.studio-learning-heading {flex-wrap:wrap}}
</style>
