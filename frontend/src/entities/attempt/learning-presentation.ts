import type { AssessmentView, TaskState } from './learning.service';

export const taskStateKeys: readonly TaskState[] = ['ACTIVE', 'COMPLETED_PASSED', 'COMPLETED_BY_LEARNER'];

export function isTaskState(value: unknown): value is TaskState {
  return typeof value === 'string' && taskStateKeys.includes(value as TaskState);
}
export function safeTaskStateKey(value: unknown): string {
  return isTaskState(value) ? 'learning.state.' + value : 'learning.unavailable';
}
export function safeRecommendationKey(value: unknown): string {
  return value === 'PASS' || value === 'REVISE' || value === 'UNCERTAIN'
    ? 'learning.recommendation.' + value : 'learning.unknownRecommendation';
}
export function safeSourceKey(value: unknown): string {
  return value === 'HUMAN_REVIEW' ? 'learning.sourceHuman'
    : value === 'VALIDATED_MEASUREMENT' ? 'learning.sourceValidated' : 'learning.sourceUnknown';
}
export function safeCriterionStatusKey(value: unknown): string {
  return value === 'ASSESSED' ? 'learning.criterionAssessed'
    : value === 'UNCERTAIN' ? 'learning.criterionUncertain'
    : value === 'NOT_ASSESSED' ? 'learning.notAssessed' : 'learning.unknown';
}
export function safeOutcomeKey(value: unknown): string {
  return value === 'MEETS' ? 'learning.meets'
    : value === 'NEEDS_WORK' ? 'learning.needsWork' : 'learning.notAssessed';
}
export function localDate(value: string | null | undefined, locale: string): string {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime())
    ? new Intl.DateTimeFormat(locale.startsWith('ru') ? 'ru-RU' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(parsed)
    : '—';
}
export function validSelectedAssessment(assessment: AssessmentView | null, selectedAttemptId: string): boolean {
  return !!assessment && assessment.attemptId === selectedAttemptId;
}
