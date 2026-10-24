export const MAX_PDF_BYTES = 20 * 1024 * 1024;
export const MAX_PDF_PAGES = 100;
export const MAX_CANVAS_PIXELS = 8_000_000;

export function pinnedPdfUrl(value: string | null, exerciseKey: string, origin: string): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('#') || value.includes('?')) return null;
  const [code, version] = exerciseKey.split('@');
  if (!code || !version || exerciseKey !== code + '@' + version) return null;
  const expected = '/api/v1/exercises/' + encodeURIComponent(code)
    + '/versions/' + encodeURIComponent(version) + '/preview/pdf';
  if (value !== expected) return null;
  const url = new URL(value, origin);
  return url.origin === origin && !url.username && !url.password ? url.href : null;
}

export function safeScoreLink(value: string | null, origin: string): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('#')) return null;
  const url = new URL(value, origin);
  if (url.origin !== origin || url.username || url.password || !url.pathname.startsWith('/api/v1/')) return null;
  return url.href;
}

export class ScoreFetchError extends Error {
  constructor(public readonly reasonCode: string) { super(reasonCode); }
}

export async function fetchBoundedPdf(url: string, signal: AbortSignal): Promise<ArrayBuffer> {
  const response = await fetch(url, { method: 'GET', credentials: 'same-origin', cache: 'no-store', signal });
  if (response.status === 404) throw new ScoreFetchError('missing');
  if (!response.ok) throw new ScoreFetchError(response.status === 401 || response.status === 403 ? 'auth' : 'network');
  if (!/^application\/pdf(?:\s*;|\s*$)/i.test(response.headers.get('content-type') ?? '')) {
    throw new ScoreFetchError('type');
  }
  const length = Number(response.headers.get('content-length'));
  if (Number.isFinite(length) && length > MAX_PDF_BYTES) throw new ScoreFetchError('limit');
  const reader = response.body?.getReader();
  if (!reader) throw new ScoreFetchError('network');
  const parts: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_PDF_BYTES) throw new ScoreFetchError('limit');
      parts.push(value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  const buffer = new Uint8Array(size);
  let cursor = 0;
  for (const part of parts) { buffer.set(part, cursor); cursor += part.byteLength; }
  if (size < 5 || String.fromCharCode(...buffer.subarray(0,5)) !== '%PDF-') {
    throw new ScoreFetchError('signature');
  }
  return buffer.buffer;
}
