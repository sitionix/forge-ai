export function clearChildren(element: HTMLElement): void {
  while (element.firstChild) {
    element.removeChild(element.firstChild);
  }
}

export function textCell(value: string | number | null | undefined): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.textContent = String(value ?? '-');
  return cell;
}
