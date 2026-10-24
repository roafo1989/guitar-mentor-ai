/**
 * In combined local QA we intentionally use one browser origin for CSRF,
 * Google OAuth state and emailed one-time links. The legacy HTTPS short alias
 * is only an entry point; it must not create a second independent session.
 */
export function canonicalUnifiedLocalUrl(current: URL, enabled: boolean): string | null {
  if (!enabled || current.origin !== 'https://guitar-mentor') return null;
  return 'http://localhost:5173' + current.pathname + current.search + current.hash;
}

export function redirectUnifiedLocalAlias(): boolean {
  const target = canonicalUnifiedLocalUrl(
    new URL(window.location.href),
    import.meta.env.VITE_GUITAR_MENTOR_UNIFIED_LOCAL === 'true',
  );
  if (!target) return false;
  window.location.replace(target);
  return true;
}
