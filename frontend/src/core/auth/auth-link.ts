/** One-time email-action fragments are consumed before Vue Router starts.
 * Never persist reset/verify tokens in storage, route state, analytics, or a DOM input.
 */
export type EmailAction = 'reset' | 'verify';
export type EmailActionLink = { action: EmailAction; token: string | null };
let pending: EmailActionLink | null = null;

export function captureEmailActionLink(): void {
  const url = new URL(window.location.href);
  const params = new URLSearchParams(url.hash.slice(1));
  const action = params.get('authAction');
  if (action !== 'reset' && action !== 'verify') return;
  const token = params.get('token');
  // Scrub the entire action fragment immediately. Keep unrelated hash parameters.
  params.delete('token');
  params.delete('authAction');
  const cleaned = url.pathname + url.search + (params.toString() ? '#' + params.toString() : '');
  window.history.replaceState(window.history.state, '', cleaned);
  pending = { action, token };
}

export function takeEmailActionLink(): EmailActionLink | null {
  const current = pending;
  pending = null;
  return current;
}
