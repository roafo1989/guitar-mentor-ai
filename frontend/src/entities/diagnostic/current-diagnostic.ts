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
): Promise<DiagnosticSession | null> {
  const key = diagnosticSelectionKey(learnerId);
  const selected = storage.getItem(key);
  if (selected) {
    try {
      const session = await diagnosticService.get(learnerId, selected);
      if (storage.getItem(key) !== selected) return resolveCurrentDiagnostic(learnerId, storage);
      return session;
    } catch (cause) {
      if (!isHttp(cause, 404)) throw cause;
      if (storage.getItem(key) !== selected) return resolveCurrentDiagnostic(learnerId, storage);
      storage.removeItem(key);
    }
  }
  const expected = storage.getItem(key);
  const current = await diagnosticService.current(learnerId, GUITAR_INSTRUMENT);
  if (storage.getItem(key) !== expected) return resolveCurrentDiagnostic(learnerId, storage);
  if (current) storage.setItem(key, current.id);
  return current;
}
