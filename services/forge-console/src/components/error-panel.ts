export function showError(element: HTMLElement | null, message: string): void {
  if (!element) {
    return;
  }
  element.textContent = message;
  element.classList.remove('hidden');
}

export function hideError(element: HTMLElement | null): void {
  if (!element) {
    return;
  }
  element.textContent = '';
  element.classList.add('hidden');
}
