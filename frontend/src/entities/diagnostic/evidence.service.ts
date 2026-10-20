export interface EvidenceMedia {
  format: string;
  bytes: number;
  status: 'UPLOADED' | 'PROCESSING' | 'PROCESSED' | 'FAILED';
  durationSeconds: number | null;
  errorCode: string | null;
  mediaState: 'AVAILABLE' | 'DELETE_PENDING' | 'DELETED';
}
export interface EvidenceEntry {
  id: string;
  title: string;
  composer: string | null;
  comment: string | null;
  submittedAt: string;
  audio: EvidenceMedia;
  score: EvidenceMedia | null;
  source: 'STUDENT_UPLOADED';
  skillEvidence: 'NOT_ASSESSED';
}
export interface EvidenceCapabilities {
  enabled: boolean;
  audioFormats: string[];
  maxAudioBytes: number;
  maxAudioSeconds: number;
  scoreFormats: string[];
  maxScoreBytes: number;
  maxItems: number;
}
function base(learnerId: string, sessionId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId) +
    '/diagnostic-sessions/' + encodeURIComponent(sessionId) + '/evidence';
}
async function response<T>(promise: Promise<Response>): Promise<T> {
  let result: Response;
  try { result = await promise; } catch { throw new Error('NETWORK_UNAVAILABLE'); }
  if (!result.ok) {
    const payload: unknown = await result.json().catch(() => null);
    const code = payload && typeof payload === 'object' && 'code' in payload
      && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED';
    throw new Error(code);
  }
  return (await result.json()) as T;
}
export const evidenceService = {
  capabilities(learnerId: string, sessionId: string) {
    return response<EvidenceCapabilities>(fetch(base(learnerId, sessionId) + '/capabilities'));
  },
  list(learnerId: string, sessionId: string) {
    return response<EvidenceEntry[]>(fetch(base(learnerId, sessionId)));
  },
  upload(learnerId: string, sessionId: string, input: {
    requestId: string; title: string; composer: string; comment: string;
    audio: File; score: File | null; signal?: AbortSignal;
  }) {
    const data = new FormData();
    data.append('requestId', input.requestId);
    data.append('title', input.title);
    if (input.composer.trim()) data.append('composer', input.composer.trim());
    if (input.comment.trim()) data.append('comment', input.comment.trim());
    data.append('audio', input.audio, input.audio.name);
    if (input.score) data.append('score', input.score, input.score.name);
    // Browser supplies a multipart boundary; do not set Content-Type manually.
    return response<EvidenceEntry>(fetch(base(learnerId, sessionId), {
      method: 'POST', body: data, signal: input.signal,
    }));
  },
};
