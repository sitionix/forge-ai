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
  sourceConnectionId: string;
  sourceOutputPortId: string;
  targetInputPortId: string;
  sourceVisualUnitKey: string;
  targetVisualUnitKey: string;
  visualType?: string;
  visualLane?: number;
  [key: string]: unknown;
}

export interface ExecutionPoint {
  x: number;
  y: number;
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
export function routeExecutionEdge(
  start: ExecutionPoint,
  end: ExecutionPoint,
  nodeBounds: Array<{ left: number; top: number; right: number; bottom: number }>,
  occupiedSegments?: Array<{ start: ExecutionPoint; end: ExecutionPoint }>
): ExecutionPoint[];
export function positiveLengthSegmentOverlap(
  leftStart: ExecutionPoint,
  leftEnd: ExecutionPoint,
  rightStart: ExecutionPoint,
  rightEnd: ExecutionPoint
): boolean;
export function routesSharePositiveLengthSegment(leftPoints: ExecutionPoint[], rightPoints: ExecutionPoint[]): boolean;
export function executionConnectionsMayBundle(
  left: Pick<ProjectedExecutionConnection, 'sourceVisualUnitKey' | 'sourceOutputPortId' | 'targetVisualUnitKey' | 'targetInputPortId'>,
  right: Pick<ProjectedExecutionConnection, 'sourceVisualUnitKey' | 'sourceOutputPortId' | 'targetVisualUnitKey' | 'targetInputPortId'>
): boolean;
