import type { ApiDiagnostic } from './common';

export interface KnowledgeStatus {
  status: string;
  module?: string;
  inventory?: {
    status?: string;
    sourceCount?: number;
    fileCount?: number;
    skippedCount?: number;
  };
  coverage?: {
    scannedFiles?: number;
    eligibleFiles?: number;
    completedAt?: string | null;
  };
}

export interface KnowledgeSource {
  sourceId: string;
  displayName: string;
  group?: string | null;
  path: string;
  rootExists: boolean;
  tags: string[];
}

export interface KnowledgeSourcesResponse {
  sources: KnowledgeSource[];
  diagnostics?: ApiDiagnostic[];
  message?: string;
}
