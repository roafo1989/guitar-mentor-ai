import { reactive } from 'vue';

type Actor = { accountId: string; learnerId: string; role: string };
export const auth = reactive<{ actor: Actor | null; ready: boolean; expired: boolean }>({
  actor: null, ready: false, expired: false,
});
let csrf: { headerName: string; token: string } | null = null;
let installed = false;

export function installAuthenticatedFetch(onSessionLost: () => void): void {
  if (import.meta.env.DEV && import.meta.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED !== 'true') return;
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
    if (response.status === 401 || response.status === 403 && auth.actor) {
      auth.actor = null;
      auth.expired = true;
      csrf = null;
      onSessionLost();
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
    auth.actor = response.ok ? await response.json() as Actor : null;
    auth.expired = false;
  } catch {
    auth.actor = null;
  } finally {
    auth.ready = true;
  }
}

export function login(): void {
  window.location.assign('/oauth2/authorization/guitar-mentor');
}

export async function logout(onLogout: () => void): Promise<void> {
  // CSRF is attached to this modifying request by the installed fetch adapter.
  const response = await fetch('/logout', { method: 'POST', credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': (document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '' } });
  if (!response.ok) throw new Error('LOGOUT_FAILED');
  auth.actor = null;
  csrf = null;
  onLogout();
}
