import type { ApiDiagnostic } from '../models/common';

export function renderDiagnostics(element: HTMLElement, diagnostics: ApiDiagnostic[]): void {
  element.replaceChildren(...diagnostics.map((diagnostic) => {
    const item = document.createElement('li');
    item.textContent = `${diagnostic.code}: ${diagnostic.message}`;
    return item;
  }));
}
