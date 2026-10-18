import type { DiagnosticSession } from '../../shared/model/diagnostic.model';

function baseUrl(learnerId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId) + '/diagnostic-sessions';
}

async function request(url: string, options?: RequestInit): Promise<DiagnosticSession> {
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
  return (await result.json()) as DiagnosticSession;
}

export const diagnosticService = {
  start(learnerId: string, instrumentCode: string) {
    return request(baseUrl(learnerId), {
      method: 'POST',
      body: JSON.stringify({ instrumentCode }),
    });
  },
  get(learnerId: string, sessionId: string) {
    return request(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId));
  },
  continueWithoutRecording(learnerId: string, sessionId: string) {
    return request(baseUrl(learnerId) + '/' + encodeURIComponent(sessionId) + '/continue', {
      method: 'POST',
    });
  },
};
