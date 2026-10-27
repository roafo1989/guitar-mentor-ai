import { reactive, watch } from 'vue';

type Actor = { accountId: string; learnerId: string; role: string };
export const auth = reactive<{ actor: Actor | null; ready: boolean; expired: boolean }>({
  actor: null, ready: false, expired: false,
});
let csrf: { headerName: string; token: string } | null = null;
let installed = false;
let contextGeneration = 0;
let refreshSequence = 0;
// Identity, not object allocation: a same-account /me refresh keeps pending work valid.
watch(() => auth.actor?.accountId ?? null, () => {
  ++contextGeneration;
  csrf = null;
}, { flush: 'sync' });
function abortStale(): never {
  throw new DOMException('Authentication context changed', 'AbortError');
}
let resetPersonalState: () => void = () => {};

export const secureUi = !(import.meta.env?.DEV === true &&
  import.meta.env?.VITE_GUITAR_MENTOR_AUTH_REQUIRED !== 'true');
export function clearActor(): void {
  ++contextGeneration;
  ++refreshSequence;
  auth.actor = null;
  auth.ready = true;
  csrf = null;
}


export function installAuthenticatedFetch(onSessionLost: () => void): void {
  if (!secureUi) return;
  resetPersonalState = onSessionLost;
  if (installed) return;
  installed = true;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const path = new URL(url, window.location.origin);
    if (path.origin !== window.location.origin || !(path.pathname.startsWith('/api/') || path.pathname === '/logout')) {
      return nativeFetch(input, init);
    }
    const generation = contextGeneration;
    const current = () => generation === contextGeneration;
    const method = (init?.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method) && !csrf) {
      await refreshCsrf(generation);
    }
    if (!current()) abortStale();
    const headers = new Headers(init?.headers || (input instanceof Request ? input.headers : undefined));
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method) && csrf) {
      headers.set(csrf.headerName, csrf.token);
    }
    const response = await nativeFetch(input, { ...init, headers, credentials: 'same-origin' }).catch(cause => {
      if (!current()) abortStale();
      throw cause;
    });
    if (!current()) abortStale();
    const accountDenied = response.status === 403 && (
      path.pathname === '/api/v1/me' ||
      (await response.clone().json().catch(() => null))?.code === 'ACCOUNT_NOT_ADMITTED'
    );
    if (!current()) abortStale();
    // /me is committed only by the latest refreshActor, including its errors.
    if (path.pathname !== '/api/v1/me' && current() && (response.status === 401 || accountDenied)) {
      clearActor();
      auth.expired = true;
      resetPersonalState();
    }
    return response;
  };
  async function refreshCsrf(generation: number): Promise<void> {
    try {
      const response = await nativeFetch('/api/v1/csrf', { credentials: 'same-origin' });
      const token = response.ok ? await response.json() as { headerName: string; token: string } : null;
      if (generation !== contextGeneration) abortStale();
      if (!token) throw new Error('CSRF_UNAVAILABLE');
      csrf = token;
    } catch (cause) {
      if (generation !== contextGeneration) abortStale();
      throw cause;
    }
  }
}

export async function refreshActor(): Promise<void> {
  const generation = contextGeneration;
  const sequence = ++refreshSequence;
  const current = () => generation === contextGeneration && sequence === refreshSequence;
  const hadActor = !!auth.actor;
  try {
    const response = await fetch('/api/v1/me', { credentials: 'same-origin' });
    const next = response.ok ? await response.json() as Actor : null;
    if (!current()) return;
    if (!next) {
      clearActor();
      auth.expired = hadActor && (response.status === 401 || response.status === 403);
      resetPersonalState();
    } else {
      if (auth.actor && auth.actor.accountId !== next.accountId) resetPersonalState();
      auth.expired = false;
      auth.ready = true;
      auth.actor = next;
    }
  } catch {
    if (!current()) return;
    clearActor();
    resetPersonalState();
  }
}

export function login(): void {
  if (import.meta.env?.VITE_GUITAR_MENTOR_OAUTH_BRIDGE !== 'true') {
    window.location.assign('/oauth2/authorization/guitar-mentor');
    return;
  }
  // Browser stays on the short HTTPS alias outside the Google authentication
  // exchange; the server binds its localhost callback to this HTTPS session.
  void (async () => {
    const response = await fetch('/api/v1/oauth-bridge/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
      credentials: 'same-origin',
    });
    if (!response.ok) throw new Error('OAUTH_BRIDGE_START_FAILED');
    const body = await response.json() as { beginUrl: string };
    if (!/^http:\/\/localhost:5173\/oauth-bridge\/begin\?nonce=[a-f0-9]{64}$/.test(body.beginUrl)) {
      throw new Error('UNEXPECTED_OAUTH_BRIDGE_URL');
    }
    window.location.assign(body.beginUrl);
  })().catch(() => {
    window.location.assign('/?auth=bridge_start_failed');
  });
}

export async function logout(onLogout: () => void): Promise<void> {
  // Cancel queued writes and identity reads as soon as logout starts.
  const generation = ++contextGeneration;
  const sequence = ++refreshSequence;
  const current = () => generation === contextGeneration && sequence === refreshSequence;
  if (!csrf) {
    const tokenResponse = await fetch('/api/v1/csrf');
    const token = tokenResponse.ok ? await tokenResponse.json() as { headerName: string; token: string } : null;
    if (!current()) return;
    if (!token) throw new Error('CSRF_UNAVAILABLE');
    csrf = token;
  }
  if (!current()) return;
  const response = await fetch('/logout', { method: 'POST', credentials: 'same-origin',
    headers: { [csrf.headerName]: csrf.token } });
  if (!current()) return;
  if (!response.ok) throw new Error('LOGOUT_FAILED');
  clearActor();
  auth.expired = false;
  onLogout();
}
