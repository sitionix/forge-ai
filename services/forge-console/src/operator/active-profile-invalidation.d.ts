export function notifyActiveProfileChanged(windowRef: any, revision: number): void;
export function observeActiveProfileChanges(windowRef: any, callback: (revision: number | null) => void): () => void;
