export type TaskState = 'ACTIVE' | 'COMPLETED_PASSED' | 'COMPLETED_BY_LEARNER';
export type DecisionAction = 'CONTINUE_PRACTICING' | 'COMPLETE';
export interface DecisionView {
  decisionId: string; taskId: string; attemptId: string; assessmentId: string | null;
  action: DecisionAction; resultingState: TaskState; resultingVersion: number;
  reasonCode: string; reasonText: string | null; recordedAt: string;
}
export interface TaskLearningView {
  taskId: string; taskState: TaskState; version: number;
  availableActions: DecisionAction[]; latestAttemptId: string | null;
  assessmentState: 'NO_ATTEMPT' | 'NO_ASSESSMENT' | 'AVAILABLE';
  latestAssessmentId: string | null; latestDecision: DecisionView | null;
  nextStepAvailability: 'NOT_IMPLEMENTED' | 'MANUAL_SELECTION' | 'SUCCESSOR_EXISTS';
}
export interface CriterionResult {
  criterionCode: string; skillCode: string; label: string; observableResult: string;
  status: string; outcome: 'MEETS' | 'NEEDS_WORK' | null;
  reasonCode: string | null; evidenceText: string | null; confirmedSkillLevel: number | null;
}
export interface AssessmentView {
  id: string; attemptId: string; revision: number; sourceKind: string;
  methodId: string; methodVersion: string; recommendation: 'PASS' | 'REVISE' | 'UNCERTAIN';
  createdAt: string; criteria: CriterionResult[]; limitations: string[];
}
export interface SkillEvidenceView {
  assessmentId: string; attemptId: string; criterionCode: string; skillCode: string;
  sourceKind: string; sourcePrincipal: string; methodId: string; methodVersion: string;
  createdAt: string; status: string; outcome: string | null;
  reasonCode: string | null; evidenceText: string | null; confirmedSkillLevel: number | null;
}
export interface Page<T> { items: T[]; nextCursor: string | null; }
export interface DecisionCommand {
  requestId: string; attemptId: string; action: DecisionAction;
  expectedVersion: number; baseAssessmentId: string | null; reason: string | null;
}
export class LearningHttpError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string) {
    super(code);
    this.name = 'LearningHttpError';
    this.status = status;
    this.code = code;
  }
}
function base(learnerId: string, sessionId: string, taskId: string): string {
  return '/api/v1/learners/' + encodeURIComponent(learnerId) +
    '/diagnostic-sessions/' + encodeURIComponent(sessionId) +
    '/tasks/' + encodeURIComponent(taskId);
}
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(path, init); }
  catch { throw new LearningHttpError(0, 'NETWORK_UNAVAILABLE'); }
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null);
    const code = typeof body === 'object' && body !== null && 'code' in body &&
      typeof body.code === 'string' ? body.code : 'UNEXPECTED_RESPONSE';
    throw new LearningHttpError(response.status, code);
  }
  return response.json() as Promise<T>;
}
function pagePath(path: string, cursor?: string): string {
  return cursor ? path + '?cursor=' + encodeURIComponent(cursor) : path;
}
export const learningService = {
  state: (learner: string, session: string, task: string) =>
    request<TaskLearningView>(base(learner, session, task) + '/learning'),
  assessments: (learner: string, session: string, task: string, attempt: string, cursor?: string) =>
    request<Page<AssessmentView>>(pagePath(base(learner, session, task) +
      '/attempts/' + encodeURIComponent(attempt) + '/assessments', cursor)),
  evidence: (learner: string, session: string, task: string, cursor?: string) =>
    request<Page<SkillEvidenceView>>(pagePath(base(learner, session, task) + '/learning/evidence', cursor)),
  decisions: (learner: string, session: string, task: string, cursor?: string) =>
    request<Page<DecisionView>>(pagePath(base(learner, session, task) + '/decisions', cursor)),
  decide: (learner: string, session: string, task: string, command: DecisionCommand) =>
    request<unknown>(base(learner, session, task) + '/decisions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(command),
    }),
};
