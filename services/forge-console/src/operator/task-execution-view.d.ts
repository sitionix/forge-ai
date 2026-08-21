export interface ExecutionVisualUnit {
  sourceNodeId: string;
  scopeMode: string;
  repositoryId: string | null;
  repositoryName: string | null;
  visualUnitKey: string;
  position: { x: number; y: number };
  layoutHeight: number;
  [key: string]: unknown;
}

export interface ProjectedExecutionConnection {
  sourceVisualUnitKey: string;
  targetVisualUnitKey: string;
  [key: string]: unknown;
}

export interface ExecutionProjectionGraph {
  nodes: ExecutionVisualUnit[];
  ports: Array<Record<string, unknown>>;
  connections: ProjectedExecutionConnection[];
}

export function visualUnitKey(sourceNodeId: string, repositoryId: string | null | undefined): string;
export function buildExecutionProjection(
  runtimeGraph: Record<string, unknown>,
  repositoryIds?: string[],
  repositories?: Array<{ id: string; name: string }>
): ExecutionProjectionGraph;
