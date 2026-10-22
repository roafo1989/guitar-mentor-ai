<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { LearningHttpError, learningService } from './learning.service';
import type { AssessmentView, DecisionCommand, DecisionView, SkillEvidenceView, TaskLearningView } from './learning.service';
import type { SubmissionAttempt } from './attempt.service';

const props = defineProps<{
  learnerId: string; sessionId: string; taskId: string; attempts: SubmissionAttempt[];
  draftBusy: boolean;
}>();
const emit = defineEmits<{ 'decision-busy': [value: boolean]; 'terminal-state': [value: boolean] }>();
const { t } = useI18n();
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

const currentAttempt = computed(() => props.attempts.find(a => a.id === selected.value));
const latestAssessment = computed(() => assessments.value[0] ?? null);
const terminal = computed(() => !!state.value && state.value.taskState !== 'ACTIVE');
watch(terminal, value => emit('terminal-state', value), { immediate: true });
const canDecide = computed(() => !!state.value && !!currentAttempt.value &&
  !terminal.value && stateFresh.value && assessmentFresh.value && !pending.value && !props.draftBusy && !sending.value && !loading.value);
const missingAssessment = computed(() => !latestAssessment.value);
function message(e: unknown): string {
  if (!(e instanceof LearningHttpError)) return t('learning.errors.UNEXPECTED_RESPONSE');
  const key = 'learning.errors.' + e.code;
  return t(key) === key ? t('learning.errors.UNEXPECTED_RESPONSE') : t(key);
}
async function refresh() {
  const seq = ++generation;
  loading.value = true; stateFresh.value = false; error.value = '';
  try {
    const next = await learningService.state(props.learnerId, props.sessionId, props.taskId);
    if (seq !== generation) return;
    state.value = next; stateFresh.value = true;
    void loadDecisionHistory(seq);
    void loadEvidence(seq);
    if (!selected.value || !props.attempts.some(a => a.id === selected.value)) {
      selected.value = next.latestAttemptId ?? props.attempts[0]?.id ?? '';
    }
    await loadAssessments(seq);
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
  assessments.value = []; assessmentCursor.value = null; assessmentFresh.value = false;
  if (!attempt) return;
  try {
    const result = await learningService.assessments(props.learnerId, props.sessionId, props.taskId, attempt);
    if (seq !== generation || attempt !== selected.value) return;
    assessments.value = result.items; assessmentCursor.value = result.nextCursor;
    assessmentFresh.value = true;
  } catch (cause) {
    if (seq === generation) error.value = message(cause);
  }
}
async function chooseAttempt(id: string) {
  if (sending.value || pending.value) return;
  selected.value = id;
  const seq = ++generation;
  loading.value = true; error.value = '';
  try { await loadAssessments(seq); }
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
      assessments.value.push(...result.items); assessmentCursor.value = result.nextCursor;
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
  finally { if (seq === generation) loading.value = false; }
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
  sending.value = true; error.value = ''; notice.value = '';
  try {
    await learningService.decide(props.learnerId, props.sessionId, props.taskId, command);
    if (seq !== generation) return;
    pending.value = null;
    await refresh();
    if (stateFresh.value) notice.value = t('learning.saved');
  } catch (cause) {
    if (seq !== generation) return;
    if (cause instanceof LearningHttpError && cause.status === 409) {
      pending.value = null;
      await refresh();
      error.value = t('learning.conflict');
    } else {
      error.value = message(cause);
    }
  } finally { sending.value = false; }
}
watch(() => [props.learnerId, props.sessionId, props.taskId], () => {
  ++generation; selected.value = ''; pending.value = null; state.value = null;
  assessments.value = []; decisions.value = []; evidence.value = [];
  evidenceCursor.value = null; evidenceError.value = '';
  decisionCursor.value = null; decisionHistoryError.value = ''; stateFresh.value = false;
  assessmentFresh.value = false; reason.value = ''; void refresh();
});
watch(() => props.attempts.map(a => `${a.id}:${a.status}:${a.assessmentAvailable}`).join(','), () => {
  if (!loading.value && !sending.value && !pending.value) void refresh();
});
onMounted(() => { void refresh(); });
onBeforeUnmount(() => { ++generation; pending.value = null; });
</script>

<template>
  <section class="homework-history" aria-labelledby="learning-title">
    <h3 id="learning-title">{{ t('learning.title') }}</h3>
    <p v-if="loading" role="status">{{ t('learning.loading') }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button type="button" class="action-button secondary-button" :disabled="loading || sending" @click="refresh">
      {{ t('learning.refresh') }}
    </button>
    <template v-if="state">
      <p role="status">{{ t('learning.state.' + state.taskState) }}</p>
      <p v-if="terminal" class="hint">{{ t('learning.nextUnavailable') }}</p>
      <label for="learning-attempt">{{ t('learning.chooseAttempt') }}</label>
      <select id="learning-attempt" :value="selected" :disabled="loading || sending" @change="chooseAttempt(($event.target as HTMLSelectElement).value)">
        <option v-for="attempt in attempts" :key="attempt.id" :value="attempt.id">
          {{ t('attempt.attempt') }} №{{ attempt.number }}
        </option>
      </select>
      <p v-if="!currentAttempt" class="hint">{{ t('learning.noAttempts') }}</p>
      <template v-else>
        <p v-if="currentAttempt.status === 'FAILED'" class="hint">{{ t('learning.technicalFailure') }}</p>
        <p v-else-if="currentAttempt.status !== 'PROCESSED'" class="hint">{{ t('learning.processing') }}</p>
        <p v-else-if="currentAttempt.status === 'PROCESSED' && missingAssessment" class="hint">{{ t('learning.noAssessment') }}</p>
        <template v-if="latestAssessment">
          <h4>{{ t('learning.feedback') }}</h4>
          <p>{{ t('learning.recommendation.' + latestAssessment.recommendation) }}</p>
          <p class="hint">{{ latestAssessment.sourceKind }} · {{ latestAssessment.methodId }} v{{ latestAssessment.methodVersion }}</p>
          <ul>
            <li v-for="criterion in latestAssessment.criteria" :key="criterion.criterionCode">
              <strong>{{ criterion.label }}</strong> — {{ criterion.outcome === 'MEETS' ? t('learning.meets') : criterion.outcome === 'NEEDS_WORK' ? t('learning.needsWork') : t('learning.notAssessed') }}
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
            <p>{{ t('learning.recommendation.' + assessment.recommendation) }} · {{ new Date(assessment.createdAt).toLocaleString() }}</p>
            <p v-for="criterion in assessment.criteria" :key="criterion.criterionCode">{{ criterion.label }}: {{ criterion.outcome ?? t('learning.unknown') }}</p>
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
      <h4>{{ t('learning.skillEvidence') }}</h4>
      <p v-if="evidenceLoading" role="status">{{ t('learning.loading') }}</p>
      <div v-if="evidenceError" role="alert">
        <p>{{ evidenceError }}</p>
        <button type="button" class="action-button secondary-button" :disabled="evidenceLoading" @click="loadEvidence()">{{ t('learning.refresh') }}</button>
      </div>
      <p class="hint">{{ t('learning.skillEvidenceHelp') }}</p>
      <p v-if="!evidence.length && !evidenceLoading && !evidenceError" class="hint">{{ t('learning.noSkillEvidence') }}</p>
      <details v-for="entry in evidence" :key="entry.assessmentId + entry.criterionCode">
        <summary>{{ entry.skillCode }} · {{ entry.outcome === 'MEETS' ? t('learning.meets') : entry.outcome === 'NEEDS_WORK' ? t('learning.needsWork') : t('learning.notAssessed') }}</summary>
        <p v-if="entry.evidenceText">{{ entry.evidenceText }}</p>
        <p>{{ t('learning.confirmedLevel') }}: {{ entry.confirmedSkillLevel ?? t('learning.unknown') }}</p>
        <p class="hint">{{ entry.methodId }} v{{ entry.methodVersion }} · {{ new Date(entry.createdAt).toLocaleString() }}</p>
      </details>
      <button v-if="evidenceCursor" class="action-button secondary-button" type="button" :disabled="evidenceLoading" @click="moreEvidence">{{ t('learning.more') }}</button>
      <h4>{{ t('learning.decisionHistory') }}</h4>
      <p v-if="decisionHistoryLoading" role="status">{{ t('learning.loading') }}</p>
      <div v-if="decisionHistoryError" role="alert">
        <p>{{ decisionHistoryError }}</p>
        <button type="button" class="action-button secondary-button" :disabled="decisionHistoryLoading" @click="loadDecisionHistory()">{{ t('learning.refresh') }}</button>
      </div>
      <p v-if="!decisions.length && !decisionHistoryLoading && !decisionHistoryError" class="hint">{{ t('learning.noDecisions') }}</p>
      <details v-for="decision in decisions" :key="decision.decisionId">
        <summary>{{ t('learning.state.' + decision.resultingState) }} · {{ new Date(decision.recordedAt).toLocaleString() }}</summary>
        <p>{{ decision.reasonText || decision.reasonCode }}</p>
      </details>
      <button v-if="decisionCursor" type="button" class="action-button secondary-button" :disabled="decisionHistoryLoading" @click="moreDecisions">{{ t('learning.more') }}</button>
    </template>
  </section>
</template>
