/**
 * Focus isolation for the Teleported tuner: the app shell becomes inert while
 * the dialog remains in document.body. A document-level guard also handles
 * attempted programmatic focus outside the modal.
 */
export function isolateDialogFocus(
  page: HTMLElement,
  dialog: HTMLElement,
  hostDocument: Document = document,
): () => void {
  const previousInert = page.inert;
  page.inert = true;

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
    page.inert = previousInert;
  };
}
