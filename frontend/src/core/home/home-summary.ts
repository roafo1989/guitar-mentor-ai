import { ApiFailure } from '../../shared/api-failure';
import { diagnosticService } from '../../entities/diagnostic/diagnostic.service';
import { resolveCurrentDiagnostic, diagnosticSelectionKey } from '../../entities/diagnostic/current-diagnostic';
import { onboardingService } from '../../entities/onboarding/onboarding.service';
import { LearningHttpError, learningService } from '../../entities/attempt/learning.service';
import type { DiagnosticTask } from '../../shared/model/diagnostic.model';

export type HomeNextStep = 'profile' | 'onboarding' | 'diagnostics' | 'choose' | 'lesson' | 'result';
export interface HomeSummary {
  step: HomeNextStep;
  title: string | null;
  exerciseCode: string | null;
  exerciseVersion: string | null;
}

// The selection is intentionally local to this browser. Do not infer from its
// absence that no server-side diagnostic sessions exist.
export const selectedSessionKey = diagnosticSelectionKey;

export async function resolveHomeSummary(
  learnerId: string | null,
  storage: Pick<Storage, 'getItem' | 'removeItem' | 'setItem'>,
  isCurrent: () => boolean = () => true,
): Promise<HomeSummary> {
  const simple = (step: HomeNextStep): HomeSummary =>
    ({ step, title: null, exerciseCode: null, exerciseVersion: null });
  if (!learnerId) return simple('profile');
  const session = await resolveCurrentDiagnostic(learnerId, storage);
  if (!session) {
    const background = await onboardingService.get(learnerId, 'ACOUSTIC_GUITAR');
    return simple(background ? 'diagnostics' : 'onboarding');
  }
  if (session.status === 'COLLECTING_EVIDENCE') return simple('diagnostics');
  if (session.status === 'READY_FOR_TASK') return simple('choose');
  if (session.status !== 'TASK_ASSIGNED') throw new ApiFailure('INVALID_RESPONSE');
  const task: DiagnosticTask | null = await diagnosticService.currentTask(learnerId, session.id);
  if (!task?.exercise) throw new ApiFailure('INVALID_RESPONSE');
  // Learning decisions are independently feature-gated. Their temporary
  // unavailability must not hide a real, version-pinned assigned exercise.
  // Never infer completion from a 503: continue to the existing lesson.
  let step: 'lesson' | 'result' = 'lesson';
  try {
    const learning = await learningService.state(learnerId, session.id, task.id);
    if (!['ACTIVE', 'COMPLETED_PASSED', 'COMPLETED_BY_LEARNER'].includes(learning.taskState)) {
      throw new ApiFailure('INVALID_RESPONSE');
    }
    step = learning.taskState === 'ACTIVE' ? 'lesson' : 'result';
  } catch (cause) {
    if (!(cause instanceof LearningHttpError && cause.status === 503 && cause.code === 'LEARNING_DISABLED')) {
      throw cause;
    }
  }
  return {
    step,
    title: task.exercise.title,
    exerciseCode: task.exercise.code,
    exerciseVersion: task.exercise.version,
  };
}
