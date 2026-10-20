/** Pure navigation policy, shared by DiagnosticView and its regression tests. */
export type IntakeNavigationAction = 'BLOCK' | 'CONFIRM_DISCARD' | 'ALLOW';

export function intakeNavigationAction(
  parentBusy: boolean,
  recordingOrUploading: boolean,
  hasUnsavedDraft: boolean,
): IntakeNavigationAction {
  if (parentBusy || recordingOrUploading) return 'BLOCK';
  return hasUnsavedDraft ? 'CONFIRM_DISCARD' : 'ALLOW';
}
