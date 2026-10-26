/** Consume legacy password-link emails before the router snapshots a secret URL. */
let pendingToken: string | null = null;
export function rememberAccountLink(hash: string): boolean {
  const params = new URLSearchParams(hash.replace(/^#/, ''));
  if (params.get('authAction') !== 'link') return false;
  const token = params.get('token');
  pendingToken = token && /^[a-f0-9]{64}$/.test(token) ? token : null;
  return true;
}
export function captureAccountLink(): void {
  const url = new URL(window.location.href);
  if (!['/profile', '/settings/account'].includes(url.pathname) || !rememberAccountLink(url.hash)) return;
  const params = new URLSearchParams(url.hash.slice(1));
  params.delete('authAction'); params.delete('token');
  window.history.replaceState(window.history.state, '', '/settings/account' + url.search + (params.size ? '#' + params : ''));
}
export function takeAccountLinkToken(): string | null {
  const token = pendingToken; pendingToken = null; return token;
}
