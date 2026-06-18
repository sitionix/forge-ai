# Graph Analysis API Adapter

Knowledge AI Structural Analysis stores facts in graph tables only.
The existing analysis endpoints keep their response shape by reading graph
tables directly.

## Primary Tables

`analysis_graph_nodes`
: Trusted and derived graph nodes.

`analysis_graph_edges`
: Trusted and derived graph edges with resolution status.

`analysis_graph_evidence`
: Line-bounded evidence tied to analyzed content hashes.

`analysis_graph_claims`
: Evidence-bound responsibility, role, hint, and side-effect claims.

`analysis_graph_diagnostics`
: Rejected candidates and controlled diagnostics grouped by stage/code/file.

`analysis_graph_resolution_candidates`
: Candidate target nodes for unresolved or ambiguous graph edges.

`analysis_jobs`
: Graph analysis job progress and stop state.

`analysis_files`
: Graph analysis file state, retries, failures, and file-level diagnostics.

## Removed Legacy Cache

Old symbol/relation projection cache tables are dropped during initialization.

## Endpoint Mapping

Graph nodes map directly to `/analysis/symbols` responses:

```text
analysis_graph_nodes.id                   -> symbolId and graphNodeId
analysis_graph_nodes.node_kind            -> kind
analysis_graph_nodes.name                 -> name
analysis_graph_nodes.line_start/end       -> lineStart/lineEnd
analysis_graph_nodes.confidence           -> confidence
analysis_graph_nodes.status               -> factStatus
RESPONSIBILITY claim                      -> responsibilitySummary and summary
ROLE claims                               -> roles[]
```

Graph edges map directly to `/analysis/relations` responses:

```text
analysis_graph_edges.id                   -> relationId and graphEdgeId
analysis_graph_edges.edge_type            -> relation
analysis_graph_edges.from_node_id         -> fromSymbolId
analysis_graph_edges.to_node_id           -> toSymbolId
analysis_graph_edges.resolution_status    -> resolutionStatus
analysis_graph_edges.confidence           -> confidence
analysis_graph_edges.status               -> factStatus
analysis_graph_edges.evidence_id          -> evidence / evidenceCount
```

Facts counts use graph rows with `TRUSTED` or `DERIVED` status only.
Rejected candidates remain diagnostics and are not counted as facts.
