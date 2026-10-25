import type { DiagnosticSession, DiagnosticTask, Exercise, ScoreEvents, DiagnosticRecommendation, SkillSnapshot, LessonHistoryPage, NextLessonResult } from '../../shared/model/diagnostic.model';
import { apiRequest, isArrayOf, isNumber, isObject, isString, isStringArray, isUuid } from '../../shared/api-failure';

function baseUrl(learnerId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId) + '/diagnostic-sessions';
}
function validSession(value: unknown): value is DiagnosticSession {
  return isObject(value) && isUuid(value.id) && isString(value.instrumentCode)
    && ['COLLECTING_EVIDENCE', 'READY_FOR_TASK', 'TASK_ASSIGNED', 'ARCHIVED'].includes(String(value.status))
    && isString(value.createdAt) && isString(value.updatedAt) && isStringArray(value.availableActions);
}
function validExercise(value: unknown): value is Exercise {
  if (!isObject(value)) return false;
  return ['code','version','title','styleCode','difficulty','meter','author','license',
    'reviewStatus','description','scoreUrl','eventsUrl'].every(key => isString(value[key]))
    && isNumber(value.bpm) && isNumber(value.measures) && isStringArray(value.skillCodes)
    && (value.contentKind == null || ['DIAGNOSTIC_PROBE', 'LEARNING_REPERTOIRE'].includes(String(value.contentKind)))
    && (value.tempoUnit == null || ['quarter', 'eighth', 'dotted-quarter'].includes(String(value.tempoUnit)))
    && (value.durationTargetSeconds == null || (isNumber(value.durationTargetSeconds)
      && value.durationTargetSeconds >= 5 && value.durationTargetSeconds <= 600))
    && (value.previewAudioUrl == null || isString(value.previewAudioUrl))
    && (value.previewPdfUrl == null || isString(value.previewPdfUrl));
}
function validTask(value: unknown): value is DiagnosticTask {
  return isObject(value) && isUuid(value.id) && isUuid(value.sessionId)
    && isString(value.assignedAt) && validExercise(value.exercise);
}
function validRecommendation(value: unknown): value is DiagnosticRecommendation {
  return isObject(value) && isUuid(value.sessionId) && isString(value.algorithmVersion)
    && ['RECOMMENDED', 'NO_MATCH'].includes(String(value.status))
    && (value.reasonCode === null || isString(value.reasonCode))
    && isArrayOf(value.items, item => isObject(item) &&
      ['exerciseCode','exerciseVersion','title','reasonText'].every(key => isString(item[key]))
      && isNumber(item.recommendedPracticeMinutes) && isStringArray(item.skillCodes)
      && isStringArray(item.reasonCodes));
}
function validSkill(value: unknown): value is SkillSnapshot {
  return isObject(value) && isString(value.skillCode) && isString(value.title)
    && isString(value.description) && isStringArray(value.prerequisites)
    && typeof value.selfReported === 'boolean' && isNumber(value.assessedLevel)
    && isString(value.evidenceStatus) && isStringArray(value.unresolvedGaps);
}
function validEvents(value: unknown): value is ScoreEvents {
  return isObject(value) && isArrayOf(value.measures, measure =>
    isObject(measure) && isNumber(measure.bar) && isString(measure.chord)
    && ['upper','bass'].every(key => isArrayOf(measure[key], event =>
      isObject(event) && isNumber(event.beat)
      && (event.length == null || isNumber(event.length))
      && (event.duration == null || isNumber(event.duration))
      && isArrayOf(event.notes, note => isObject(note) && isNumber(note.string) && isNumber(note.fret)))));
}
export const diagnosticService = {
  start(learnerId: string, instrumentCode: string) {
    return apiRequest(baseUrl(learnerId),
      { method: 'POST', body: JSON.stringify({ instrumentCode }) }, validSession);
  },
  current(learnerId: string, instrumentCode: string) {
    return apiRequest(baseUrl(learnerId) + '/current?instrumentCode=' + encodeURIComponent(instrumentCode),
      { cache: 'no-store' }, validSession, true);
  },
  get(learnerId: string, sessionId: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId), undefined, validSession);
  },
  continueWithoutRecording(learnerId: string, sessionId: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) + '/continue',
      { method: 'POST' }, validSession);
  },
  listExercises() {
    return apiRequest('/api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE',
      undefined, (v): v is Exercise[] => isArrayOf(v, validExercise));
  },
  recommendations(learnerId: string, sessionId: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) +
      '/exercise-recommendations', undefined, validRecommendation);
  },
  skillMap(learnerId: string, instrumentCode: string) {
    return apiRequest('/api/v1/learners/' + encodeURIComponent(learnerId) +
      '/instruments/' + encodeURIComponent(instrumentCode) + '/skills',
      undefined, (v): v is SkillSnapshot[] => isArrayOf(v, validSkill));
  },
  currentTask(learnerId: string, sessionId: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) +
      '/tasks', undefined, validTask, true);
  },
  assignTask(learnerId: string, sessionId: string, exerciseCode: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) + '/tasks',
      { method: 'POST', body: JSON.stringify({ exerciseCode }) }, validTask);
  },

  history(learnerId: string, cursor?: string) {
    const params = new URLSearchParams({ instrumentCode: 'ACOUSTIC_GUITAR', limit: '20' });
    if (cursor) params.set('cursor', cursor);
    return apiRequest('/api/v1/learners/' + encodeURIComponent(learnerId) + '/lessons?' + params,
      { cache: 'no-store' }, (v): v is LessonHistoryPage => isObject(v)
        && isArrayOf(v.items, item => isObject(item) && isUuid(item.sessionId)
          && isUuid(item.taskId) && isString(item.exerciseCode)
          && isString(item.exerciseVersion) && isString(item.title)
          && isString(item.assignedAt)
          && (item.taskState == null || isString(item.taskState))
          && typeof item.isCurrent === 'boolean')
        && (v.nextCursor === null || isString(v.nextCursor)));
  },
  nextLesson(learnerId: string, sessionId: string, taskId: string,
      requestId: string, expectedVersion: number, exerciseCode: string, exerciseVersion: string) {
    return apiRequest(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) +
      '/tasks/' + encodeURIComponent(taskId) + '/next-lesson', {
        method: 'POST',
        body: JSON.stringify({ requestId, expectedVersion, exerciseCode, exerciseVersion }),
      }, (v): v is NextLessonResult => isObject(v) && isUuid(v.transitionId)
        && isUuid(v.sourceSessionId) && isUuid(v.sourceTaskId)
        && validSession(v.session) && validTask(v.task));
  },
  scoreEvents(eventsUrl: string) {
    return apiRequest(eventsUrl, undefined, validEvents);
  },
};
