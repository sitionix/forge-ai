export type InventoryStatus = 'AVAILABLE' | 'EMPTY' | 'UNSUPPORTED' | 'UNAVAILABLE' | 'FAILED';
export type LibraryCategory = 'skills' | 'agents' | 'flows' | 'tools';
export interface LibraryItem { id: string; name: string; description: string; source: string | null }
export interface InventoryResult { status: InventoryStatus; items: LibraryItem[]; diagnostics: string[] }
export interface AiLibrarySnapshot {
  projectId: string; profileRevision: number | null;
  provider: { profileRevision: number; providerId: string; modelId: string; effortId: string | null; providerDisplayName: string | null; modelDisplayName: string | null } | null;
  categories: Record<LibraryCategory, InventoryResult>;
}
