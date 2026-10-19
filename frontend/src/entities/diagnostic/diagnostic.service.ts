import type { DiagnosticSession, DiagnosticTask, Exercise, ScoreEvents, DiagnosticRecommendation, SkillSnapshot } from '../../shared/model/diagnostic.model';

function baseUrl(learnerId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId) + '/diagnostic-sessions';
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  let result: Response;
  try {
    result = await fetch(url, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    });
  } catch {
    throw new Error('NETWORK_UNAVAILABLE');
  }

  if (!result.ok) {
    const payload: unknown = await result.json().catch(() => null);
    const message = typeof payload === 'object' && payload !== null
      && 'error' in payload && typeof payload.error === 'string'
      ? payload.error : result.statusText;
    throw new Error('HTTP_' + result.status + ': ' + message);
  }
  if (result.status === 204) return null as T;
  return (await result.json()) as T;
}

export const diagnosticService = {
  start(learnerId: string, instrumentCode: string) {
    return request<DiagnosticSession>(baseUrl(learnerId), {
      method: 'POST',
      body: JSON.stringify({ instrumentCode }),
    });
  },
  get(learnerId: string, sessionId: string) {
    return request<DiagnosticSession>(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId));
  },
  continueWithoutRecording(learnerId: string, sessionId: string) {
    return request<DiagnosticSession>(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) + '/continue', {
      method: 'POST',
    });
  },
  listExercises() {
    return request<Exercise[]>('/api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE');
  },
  recommendations(learnerId: string, sessionId: string) {
    return request<DiagnosticRecommendation>(baseUrl(learnerId) + '/' +
      encodeURIComponent(sessionId) + '/exercise-recommendations');
  },
  skillMap(learnerId: string, instrumentCode: string) {
    return request<SkillSnapshot[]>('/api/v1/learners/' + encodeURIComponent(learnerId) +
      '/instruments/' + encodeURIComponent(instrumentCode) + '/skills');
  },
  currentTask(learnerId: string, sessionId: string) {
    return request<DiagnosticTask | null>(baseUrl(learnerId) + '/' +
      encodeURIComponent(sessionId) + '/tasks');
  },
  assignTask(learnerId: string, sessionId: string, exerciseCode: string) {
    return request<DiagnosticTask>(baseUrl(learnerId) + '/' +
      encodeURIComponent(sessionId) + '/tasks', {
      method: 'POST', body: JSON.stringify({ exerciseCode }),
    });
  },
  scoreEvents(eventsUrl: string) {
    // The URL comes from our own trusted backend exercise catalog.
    return request<ScoreEvents>(eventsUrl);
  },
};
