/** Shared intake policy for Continue, Vue Router leave guards and reload warnings. */
export type IntakeNavigationAction = 'BLOCK' | 'CONFIRM_DISCARD' | 'ALLOW';

export function intakeNavigationAction(
  parentBusy: boolean,
  recordingOrUploading: boolean,
  hasUnsavedDraft: boolean,
): IntakeNavigationAction {
  if (parentBusy || recordingOrUploading) return 'BLOCK';
  return hasUnsavedDraft ? 'CONFIRM_DISCARD' : 'ALLOW';
}

/** A declined confirmation or active recording/upload MUST abort router navigation. */
export function permitIntakeNavigation(
  parentBusy: boolean,
  recordingOrUploading: boolean,
  hasUnsavedDraft: boolean,
  confirmDiscard: () => boolean,
): boolean {
  const action = intakeNavigationAction(parentBusy, recordingOrUploading, hasUnsavedDraft);
  if (action === 'BLOCK') return false;
  return action === 'ALLOW' || confirmDiscard();
}

/** Native beforeunload is the only browser-level safeguard for F5/tab close. */
export function shouldWarnBeforeUnload(
  isCollectingEvidence: boolean,
  recordingOrUploading: boolean,
  hasUnsavedDraft: boolean,
): boolean {
  return isCollectingEvidence && (recordingOrUploading || hasUnsavedDraft);
}
