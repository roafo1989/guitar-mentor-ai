import { reactive } from 'vue';

type Actor = { accountId: string; learnerId: string; role: string };
export const auth = reactive<{ actor: Actor | null; ready: boolean; expired: boolean }>({
  actor: null, ready: false, expired: false,
});
let csrf: { headerName: string; token: string } | null = null;
let installed = false;
let resetPersonalState: () => void = () => {};

export const secureUi = !(import.meta.env?.DEV === true &&
  import.meta.env?.VITE_GUITAR_MENTOR_AUTH_REQUIRED !== 'true');
export function clearActor(): void {
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
    if (path.origin !== window.location.origin || !path.pathname.startsWith('/api/')) {
      return nativeFetch(input, init);
    }
    const method = (init?.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method) && !csrf) {
      await refreshCsrf();
    }
    const headers = new Headers(init?.headers || (input instanceof Request ? input.headers : undefined));
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method) && csrf) {
      headers.set(csrf.headerName, csrf.token);
    }
    const response = await nativeFetch(input, { ...init, headers, credentials: 'same-origin' });
    const accountDenied = response.status === 403 && (
      path.pathname === '/api/v1/me' ||
      (await response.clone().json().catch(() => null))?.code === 'ACCOUNT_NOT_ADMITTED'
    );
    if (response.status === 401 || accountDenied) {
      clearActor();
      auth.expired = true;
      resetPersonalState();
    }
    return response;
  };
  async function refreshCsrf(): Promise<void> {
    const response = await nativeFetch('/api/v1/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error('CSRF_UNAVAILABLE');
    csrf = await response.json() as { headerName: string; token: string };
  }
}

export async function refreshActor(): Promise<void> {
  try {
    const response = await fetch('/api/v1/me', { credentials: 'same-origin' });
    const next = response.ok ? await response.json() as Actor : null;
    if (!next) {
      clearActor();
      resetPersonalState();
    } else {
      if (auth.actor && auth.actor.accountId !== next.accountId) {
        resetPersonalState();
        csrf = null;
      }
      auth.actor = next;
    }
    auth.expired = false;
  } catch {
    clearActor();
  } finally {
    auth.ready = true;
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
  // CSRF is attached to this modifying request by the installed fetch adapter.
  if (!csrf) {
    const tokenResponse = await fetch('/api/v1/csrf');
    if (!tokenResponse.ok) throw new Error('CSRF_UNAVAILABLE');
    csrf = await tokenResponse.json() as { headerName: string; token: string };
  }
  const response = await fetch('/logout', { method: 'POST', credentials: 'same-origin',
    headers: { [csrf.headerName]: csrf.token } });
  if (!response.ok) throw new Error('LOGOUT_FAILED');
  clearActor();
  auth.expired = false;
  onLogout();
}
