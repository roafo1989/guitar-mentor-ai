export type DiagnosticStatus = 'COLLECTING_EVIDENCE' | 'READY_FOR_TASK';
export interface DiagnosticSession {
  id: string;
  instrumentCode: string;
  status: DiagnosticStatus;
  createdAt: string;
  updatedAt: string;
  availableActions: string[];
}
