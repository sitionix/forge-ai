export function setStatusText(element: HTMLElement | null, value: string | number | null | undefined): void {
  if (!element) {
    return;
  }
  element.textContent = String(value ?? '-');
}
