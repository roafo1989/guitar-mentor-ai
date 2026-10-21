/**
 * Browser-only, best-effort preference. The language is intentionally not
 * part of learner/session state and must never block application startup.
 */
export const LOCALE_STORAGE_KEY = 'guitar-mentor.locale';
export type SupportedLocale = 'ru' | 'en';
export type LocaleStorage = Pick<Storage, 'getItem' | 'setItem'>;

export function normalizeLocale(value: unknown): SupportedLocale {
  return value === 'en' ? 'en' : 'ru';
}

function browserStorage(): LocaleStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    // Accessing the localStorage getter can throw (e.g. restricted origin).
    return null;
  }
}

export function readStoredLocale(storage: LocaleStorage | null = browserStorage()): SupportedLocale {
  try {
    return normalizeLocale(storage?.getItem(LOCALE_STORAGE_KEY));
  } catch {
    return 'ru';
  }
}

export function saveLocale(locale: string, storage: LocaleStorage | null = browserStorage()): void {
  // Never persist arbitrary values, including strings from compromised storage.
  if (locale !== 'ru' && locale !== 'en') return;
  try {
    storage?.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // Language still changes in memory when writing is denied or quota is full.
  }
}
