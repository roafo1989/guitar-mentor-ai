import { diagnosticService } from '../../entities/diagnostic/diagnostic.service';
import { onboardingService } from '../../entities/onboarding/onboarding.service';
import { learningService } from '../../entities/attempt/learning.service';
import type { DiagnosticSession, DiagnosticTask } from '../../shared/model/diagnostic.model';

export type HomeNextStep = 'profile' | 'onboarding' | 'diagnostics' | 'choose' | 'lesson' | 'result';
export interface HomeSummary {
  step: HomeNextStep;
  title: string | null;
  exerciseCode: string | null;
  exerciseVersion: string | null;
}

// The selection is intentionally local to this browser. Do not infer from its
// absence that no server-side diagnostic sessions exist.
export function selectedSessionKey(learnerId: string): string {
  return 'guitar-mentor.diagnostic.' + learnerId + '.ACOUSTIC_GUITAR';
}

export async function resolveHomeSummary(
  learnerId: string | null,
  storage: Pick<Storage, 'getItem' | 'removeItem'>,
): Promise<HomeSummary> {
  const simple = (step: HomeNextStep): HomeSummary =>
    ({ step, title: null, exerciseCode: null, exerciseVersion: null });
  if (!learnerId) return simple('profile');
  const key = selectedSessionKey(learnerId);
  const selected = storage.getItem(key);
  if (!selected) {
    const background = await onboardingService.get(learnerId, 'ACOUSTIC_GUITAR');
    return simple(background ? 'diagnostics' : 'onboarding');
  }

  let session: DiagnosticSession;
  try {
    session = await diagnosticService.get(learnerId, selected);
  } catch (cause) {
    // Only a 404 from GET session invalidates this browser's selection.
    if (cause instanceof Error && cause.message.startsWith('HTTP_404')) {
      storage.removeItem(key);
      const background = await onboardingService.get(learnerId, 'ACOUSTIC_GUITAR');
      return simple(background ? 'diagnostics' : 'onboarding');
    }
    throw cause;
  }
  if (session.status === 'COLLECTING_EVIDENCE') return simple('diagnostics');
  if (session.status === 'READY_FOR_TASK') return simple('choose');
  if (session.status !== 'TASK_ASSIGNED') throw new Error('INVALID_SESSION');
  const task: DiagnosticTask | null = await diagnosticService.currentTask(learnerId, session.id);
  if (!task?.exercise) throw new Error('INVALID_TASK');
  const learning = await learningService.state(learnerId, session.id, task.id);
  if (!['ACTIVE', 'COMPLETED_PASSED', 'COMPLETED_BY_LEARNER'].includes(learning.taskState)) {
    throw new Error('INVALID_LEARNING_STATE');
  }
  return {
    step: learning.taskState === 'ACTIVE' ? 'lesson' : 'result',
    title: task.exercise.title,
    exerciseCode: task.exercise.code,
    exerciseVersion: task.exercise.version,
  };
}
