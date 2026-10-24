// Read a one-time server-generated OAuth handoff ticket before Vue Router
// snapshots the hash. The ticket never enters component markup or analytics.
let pending: string | null = null;
export function captureOAuthBridgeTicket(): void {
  const url = new URL(window.location.href);
  const params = new URLSearchParams(url.hash.slice(1));
  if (!params.has('oauthBridge')) return;
  pending = params.get('oauthBridge');
  params.delete('oauthBridge');
  const cleaned = url.pathname + url.search + (params.toString() ? '#' + params.toString() : '');
  window.history.replaceState(window.history.state, '', cleaned);
}
export function takeOAuthBridgeTicket(): string | null {
  const ticket = pending;
  pending = null;
  return ticket;
}
