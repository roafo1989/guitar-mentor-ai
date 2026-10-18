export type DiagnosticStatus = 'COLLECTING_EVIDENCE' | 'READY_FOR_TASK' | 'TASK_ASSIGNED';
export interface DiagnosticSession {
  id: string;
  instrumentCode: string;
  status: DiagnosticStatus;
  createdAt: string;
  updatedAt: string;
  availableActions: string[];
}

export interface Exercise {
  code: string;
  version: string;
  title: string;
  styleCode: string;
  difficulty: string;
  bpm: number;
  measures: number;
  meter: string;
  author: string;
  license: string;
  reviewStatus: string;
  description: string;
  skillCodes: string[];
  scoreUrl: string;
  eventsUrl: string;
}
export interface DiagnosticTask {
  id: string;
  sessionId: string;
  assignedAt: string;
  exercise: Exercise;
}
export interface ScoreNote { string: number; fret: number; }
export interface ScoreEvent { beat: number; length?: number; duration?: number; notes: ScoreNote[]; }
export interface ScoreMeasure { bar: number; chord: string; upper: ScoreEvent[]; bass: ScoreEvent[]; }
export interface ScoreEvents { measures: ScoreMeasure[]; }
