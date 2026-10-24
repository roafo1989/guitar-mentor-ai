import type { LearningBackground } from '../../shared/model/onboarding.model';
import { apiRequest, isArrayOf, isNumber, isObject, isString, isStringArray } from '../../shared/api-failure';

const baseUrl = '/api/v1/learners';
function url(learnerId: string, instrumentCode: string): string {
  return baseUrl + '/' + encodeURIComponent(learnerId)
    + '/instruments/' + encodeURIComponent(instrumentCode) + '/background';
}
function validBackground(value: unknown): value is LearningBackground {
  return isObject(value) && ['NONE', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED'].includes(String(value.experienceLevel))
    && isNumber(value.yearsPlaying) && isStringArray(value.skills)
    && isArrayOf(value.knownPieces, piece =>
      isObject(piece) && isString(piece.title) && isString(piece.artist))
    && isStringArray(value.goals) && isNumber(value.practiceMinutes)
    && isNumber(value.sessionsPerWeek) && (isString(value.notes) || value.notes == null);
}
function call(learnerId: string, instrumentCode: string, options?: RequestInit): Promise<LearningBackground | null> {
  return apiRequest(url(learnerId, instrumentCode), options,
    (value): value is LearningBackground | null => value === null || validBackground(value), true);
}
export const onboardingService = {
  get(learnerId: string, instrumentCode: string) {
    return call(learnerId, instrumentCode);
  },
  save(learnerId: string, instrumentCode: string, background: LearningBackground) {
    return call(learnerId, instrumentCode, { method: 'PUT', body: JSON.stringify(background) });
  },
};
