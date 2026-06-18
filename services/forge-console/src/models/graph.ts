export interface GraphNode {
  id: string;
  label?: string;
  name?: string;
  nodeKind?: string;
  sourceId?: string;
}

export interface GraphEdge {
  id: string;
  from: string;
  to: string;
  edgeType?: string;
}

export interface KnowledgeGraphResponse {
  nodes: GraphNode[];
  edges: GraphEdge[];
  meta?: {
    totalNodeCount?: number;
    totalEdgeCount?: number;
    truncated?: boolean;
  };
}
