/**
 * Guard keyboard/programmatic focus inside the Teleported tuner dialog.
 * App.vue is the sole owner of app-shell.inert via its reactive binding.
 * This helper MUST NOT capture, set or restore that property: a post-flush
 * cleanup could otherwise re-inert the entire app after the parent closes it.
 */
export function isolateDialogFocus(
  dialog: HTMLElement,
  hostDocument: Document = document,
): () => void {
  const keepInside = (event: FocusEvent) => {
    if (!dialog.contains(event.target as Node)) dialog.focus();
  };
  hostDocument.addEventListener('focusin', keepInside, true);
  dialog.focus();

  let released = false;
  return () => {
    if (released) return;
    released = true;
    hostDocument.removeEventListener('focusin', keepInside, true);
  };
}
