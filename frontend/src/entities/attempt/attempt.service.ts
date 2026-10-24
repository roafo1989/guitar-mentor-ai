export class AttemptHttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super('Attempt request failed (' + status + ')');
    this.name = 'AttemptHttpError';
  }
}

export interface SubmissionAttempt {
  id: string;
  taskId: string;
  number: number;
  submittedAt: string;
  status: 'UPLOADED' | 'PROCESSING' | 'PROCESSED' | 'FAILED';
  comment: string | null;
  format: string;
  bytes: number;
  assessmentAvailable: boolean;
  processingStartedAt: string | null;
  processingFinishedAt: string | null;
  processingErrorCode: 'MEDIA_MISSING' | 'SIZE_MISMATCH' | 'INVALID_FORMAT'
    | 'INVALID_KEY' | 'IO_ERROR' | 'TECHNICAL_ERROR'
    | 'DURATION_EXCEEDED' | 'INVALID_MEDIA' | 'PROBE_UNAVAILABLE'
    | 'PROBE_TIMEOUT' | 'PROBE_INTERRUPTED' | 'PROCESSING_INTERRUPTED' | 'RETENTION_EXPIRED' | null;
  audioDurationSeconds: number | null;
  mediaState?: 'AVAILABLE' | 'DELETE_PENDING' | 'DELETED';
  mediaDeletedAt?: string | null;

}
export interface AttemptPage {
  items: SubmissionAttempt[];
  nextBeforeNumber: number | null;
  hasMore: boolean;
}

export interface UploadCapabilities {
  enabled: boolean;
  maxBytes: number;
  formats: string[];
}

function url(learnerId: string, sessionId: string, taskId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId)
    + '/diagnostic-sessions/' + encodeURIComponent(sessionId)
    + '/tasks/' + encodeURIComponent(taskId) + '/attempts';
}
async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const payload: unknown = await res.json().catch(() => null);
    // Only structured, server-controlled codes are used for localized UI messages.
    const code = typeof payload === 'object' && payload !== null &&
      'code' in payload && typeof payload.code === 'string'
      ? payload.code : 'UNEXPECTED_RESPONSE';
    throw new AttemptHttpError(res.status, code);
  }
  return await res.json() as T;
}

export const attemptService = {
  async capabilities(learnerId: string, sessionId: string, taskId: string) {
    const res = await fetch(url(learnerId, sessionId, taskId) + '/capabilities');
    return parse<UploadCapabilities>(res);
  },
  async list(learnerId: string, sessionId: string, taskId: string) {
    const res = await fetch(url(learnerId, sessionId, taskId));
    return parse<SubmissionAttempt[]>(res);
  },
  async page(learnerId: string, sessionId: string, taskId: string, beforeNumber?: number) {
    const params = new URLSearchParams({ limit: '20' });
    if (beforeNumber !== undefined) params.set('beforeNumber', String(beforeNumber));
    const res = await fetch(url(learnerId, sessionId, taskId) + '/page?' + params, { cache: 'no-store' });
    return parse<AttemptPage>(res);
  },
  async detail(learnerId: string, sessionId: string, taskId: string, id: string) {
    const res = await fetch(url(learnerId, sessionId, taskId) + '/' + encodeURIComponent(id), { cache: 'no-store' });
    return parse<SubmissionAttempt>(res);
  },
  async upload(learnerId: string, sessionId: string, taskId: string,
    file: File, comment: string, requestId: string) {
    const payload = new FormData();
    payload.append('file', file);
    payload.append('requestId', requestId);
    payload.append('comment', comment);
    // Let the browser set multipart/form-data with the correct boundary.
    const res = await fetch(url(learnerId, sessionId, taskId), {
      method: 'POST', body: payload,
    });
    return parse<SubmissionAttempt>(res);
  },
};
