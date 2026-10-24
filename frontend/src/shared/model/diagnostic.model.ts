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
  contentKind?: 'DIAGNOSTIC_PROBE' | 'LEARNING_REPERTOIRE';
  tempoUnit?: 'quarter' | 'eighth' | 'dotted-quarter';
  durationTargetSeconds?: number | null;
  author: string;
  license: string;
  reviewStatus: string;
  description: string | null;
  learningGoal?: string | null;
  practiceInstructions?: string | null;
  successCriteria?: string[] | null;
  tuning?: string | null;
  recommendedPracticeMinutes?: number | null;
  skillCodes: string[];
  scoreUrl: string;
  eventsUrl: string;
  previewAudioUrl: string | null;
  previewPdfUrl: string | null;
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

export interface DiagnosticRecommendation {
  sessionId: string;
  algorithmVersion: string;
  status: 'RECOMMENDED' | 'NO_MATCH';
  reasonCode: string | null;
  items: Array<{
    exerciseCode: string;
    exerciseVersion: string;
    title: string;
    recommendedPracticeMinutes: number;
    skillCodes: string[];
    reasonCodes: string[];
    reasonText: string;
  }>;
}
export interface SkillSnapshot {
  skillCode: string;
  title: string;
  description: string;
  prerequisites: string[];
  selfReported: boolean;
  assessedLevel: number;
  evidenceStatus: string;
  unresolvedGaps: string[];
}
