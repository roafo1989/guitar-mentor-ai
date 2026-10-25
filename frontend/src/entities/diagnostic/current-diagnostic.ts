import { isHttp } from '../../shared/api-failure';
import { diagnosticService } from './diagnostic.service';
import type { DiagnosticSession } from '../../shared/model/diagnostic.model';

export const GUITAR_INSTRUMENT = 'ACOUSTIC_GUITAR';
export function diagnosticSelectionKey(learnerId: string): string {
  return 'guitar-mentor.diagnostic.' + learnerId + '.' + GUITAR_INSTRUMENT;
}

/** Never create or reassign work when resuming. An unreadable session is not an absent session. */
export async function resolveCurrentDiagnostic(
  learnerId: string,
  storage: Pick<Storage, 'getItem' | 'removeItem' | 'setItem'>,
  isCurrent: () => boolean = () => true,
): Promise<DiagnosticSession | null> {
  const verify = () => {
    if (!isCurrent()) throw Object.assign(new Error('Stale diagnostic restore'), { name: 'AbortError' });
  };
  verify();
  const key = diagnosticSelectionKey(learnerId);
  const selected = storage.getItem(key);
  if (selected) {
    try {
      const session = await diagnosticService.get(learnerId, selected);
      verify();
      if (storage.getItem(key) !== selected) return resolveCurrentDiagnostic(learnerId, storage, isCurrent);
      if (session.status !== 'ARCHIVED') return session;
      // Keep the last verified selection until authoritative current succeeds.
      // A network error is not proof that the archived session has no successor.
    } catch (cause) {
      verify();
      if (!isHttp(cause, 404)) throw cause;
      if (storage.getItem(key) !== selected) return resolveCurrentDiagnostic(learnerId, storage, isCurrent);
      storage.removeItem(key);
    }
  }
  const expected = storage.getItem(key);
  const current = await diagnosticService.current(learnerId, GUITAR_INSTRUMENT);
  verify();
  if (storage.getItem(key) !== expected) return resolveCurrentDiagnostic(learnerId, storage, isCurrent);
  if (current) storage.setItem(key, current.id);
  else if (selected && storage.getItem(key) === selected) storage.removeItem(key);
  return current;
}
