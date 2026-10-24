// Safe transport boundary for learner, onboarding and diagnostics.
// Never keep or display server-provided error bodies, statusText or stacks.
export type ApiFailureKind = 'NETWORK' | 'HTTP' | 'INVALID_RESPONSE';

export class ApiFailure extends Error {
  readonly kind: ApiFailureKind;
  readonly status: number | null;
  readonly code: string | null;
  constructor(kind: ApiFailureKind, status: number | null = null, code: string | null = null) {
    super(kind === 'HTTP' ? 'HTTP_' + status : kind);
    this.name = 'ApiFailure';
    this.kind = kind;
    this.status = status;
    this.code = code;
  }
}

const allowedCodes = new Set([
  'RESOURCE_NOT_FOUND', 'VALIDATION_ERROR', 'INVALID_REQUEST',
  'INVALID_LEARNER_REFERENCE', 'INVALID_DIAGNOSTIC_REFERENCE',
  'INVALID_INSTRUMENT', 'CONFLICT', 'INTERNAL_ERROR',
]);
export function isHttp(cause: unknown, status: number): cause is ApiFailure {
  return cause instanceof ApiFailure && cause.kind === 'HTTP' && cause.status === status;
}
export function asApiFailure(cause: unknown): ApiFailure {
  return cause instanceof ApiFailure ? cause : new ApiFailure('INVALID_RESPONSE');
}
export function apiFailureKey(cause: unknown): string {
  const failure = asApiFailure(cause);
  if (failure.kind === 'NETWORK') return 'apiErrors.network';
  if (failure.kind === 'INVALID_RESPONSE') return 'apiErrors.invalid';
  switch (failure.status) {
    case 400: case 422: return 'apiErrors.validation';
    case 401: case 403: return 'apiErrors.forbidden';
    case 404: return 'apiErrors.notFound';
    case 409: return 'apiErrors.conflict';
    case 413: return 'apiErrors.tooLarge';
    default: return 'apiErrors.http';
  }
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
export const isString = (value: unknown): value is string => typeof value === 'string';
export const isNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);
export const isStringArray = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every(isString);
export const isUuid = (value: unknown): value is string =>
  isString(value) && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export const isArrayOf = (value: unknown, check: (entry: unknown) => boolean): boolean =>
  Array.isArray(value) && value.every(check);

export async function apiRequest<T>(
  url: string,
  options: RequestInit | undefined,
  valid: (payload: unknown) => payload is T,
  allowEmpty = false,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    });
  } catch (cause) {
    // An aborted request is a stale context, not an offline error.
    if ((cause instanceof Error && cause.name === 'AbortError') || options?.signal?.aborted) throw cause;
    throw new ApiFailure('NETWORK');
  }
  // Classify HTTP before trying to parse an HTML/empty error body.
  if (!response.ok) {
    // HTTP is classified before parsing; HTML and missing bodies keep their status.
    // Extract only a recognized machine code; never retain raw message/error text.
    let code: string | null = null;
    try {
      const payload: unknown = await response.json();
      if (isObject(payload) && isString(payload.code) && allowedCodes.has(payload.code))
        code = payload.code;
    } catch { /* HTML or empty error response is still an HTTP failure. */ }
    throw new ApiFailure('HTTP', response.status, code);
  }
  if (allowEmpty && response.status === 204) return null as T;
  let payload: unknown;
  try {
    payload = await response.json();
  } catch (cause) {
    if ((cause instanceof Error && cause.name === 'AbortError') || options?.signal?.aborted) throw cause;
    throw new ApiFailure('INVALID_RESPONSE');
  }
  if (!valid(payload)) throw new ApiFailure('INVALID_RESPONSE');
  return payload;
}
