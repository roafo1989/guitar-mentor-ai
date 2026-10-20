// Browser-only microphone lease, shared by tuner and performance recorder.
export type AudioOwner = 'tuner' | 'recorder';
let owner: { type: AudioOwner; token: symbol; stop: () => void } | null = null;
export function acquireAudio(type: AudioOwner, token: symbol, stop: () => void): boolean {
  if (owner?.token === token) return true;
  // Recording preempts tuning; tuning may never cancel a recording/draft.
  if (type === 'recorder' && owner?.type === 'tuner') owner.stop();
  if (owner) return false;
  owner = { type, token, stop };
  return true;
}
export function releaseAudio(token: symbol): void {
  if (owner?.token === token) owner = null;
}
export function audioOwner(): AudioOwner | null {
  return owner?.type ?? null;
}
