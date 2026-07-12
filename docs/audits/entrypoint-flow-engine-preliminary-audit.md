# Entrypoint flow engine preliminary audit

Audit date: 2026-07-12. The SQLite inspection was read-only and used
`var/knowledge/knowledge.sqlite`.

## Previous construction and consumers

The previous construction chain was:

1. `KnowledgeQueryService.query_with_flow_units`
2. `GraphSliceQueryService.build`
3. `FlowPathExtractor.extract`
4. `FlowBuilder.build`
5. `_upstream_paths`, `_downstream_paths`, and `_combine_paths`
6. path-specific evidence hydration in `AnalysisStore.hydrate_flow_unit_evidence`
7. `FlowExplanationContextPacker.pack` per `FlowUnit`

The path domain consisted of `FlowBuildResult`, `FlowUnit`, `FlowUnitKey`,
`FlowUnitOrigin`, `FlowStopReason`, `_TraversalPath`, `_BuiltFlowPath`, and the
public `KnowledgeQueryFlowPath`. Public path state was exposed through
`flowPaths`, `verifiedPaths`, `flowPathCount`, node/edge ID arrays, boundary
edge ID arrays, evidence ID arrays, and a single path stop reason.

Direct consumers existed in Knowledge API and OpenAPI tests, the explanation
and tool-context projectors, Jarvis query schema/fixtures, the Nexus Java proxy
record and proxy fixtures, and the Console query preview. All were changed to
consume `flows`; no repository consumer requires a path adapter.

## Persisted graph contract

Entrypoints are persisted as `ENTRYPOINT_HINT` claims whose statuses are
selected through `graph_query_contract().statuses_for_current_graph()`.
`CALLS` is a typed graph edge. Resolved edges use `RESOLVED`; boundaries use
typed resolution statuses including `UNRESOLVED`, `EXTERNAL_TARGET`, and
`DYNAMIC_TARGET`, with `unresolved_target_json` retaining target descriptors.
Edge evidence ownership is represented by `analysis_graph_edge_evidence`.

Current graph membership is identity-based: graph rows must match both an
`analysis_files` row and current `files` inventory row on source, relative
path, and content hash. `analysis_graph_state.content_identity` supplies the
current graph revision, and semantic state is independently checked against
that revision.

Production/test eligibility is persisted generically as `flow_domain`.
Current data contained `CODE` and `TEST` graph facts, but the configured
source-root policy did not cover nested module roots such as
`**/src/test/**`. Generic source-root patterns were added and the current
inventory was refreshed without resetting the database or rebuilding the
analysis graph. Query-time path or naming classification is not used.

## SQLite shape and distributions

The inspected current `stsssox` revision was
`stsssox:current-graph:0115389d0bf7be76d2675ef2d564cc077fea45bcbe2bd4915a4ffb1224bcf4d2`.
It contained 852 current nodes and 1,336 current `CALLS` edges: 90 resolved,
436 external, and 810 unresolved. There were 65 explicit entrypoint claims,
no resolved-call strongly connected cycle, maximum resolved incoming fan-out
6, maximum outgoing fan-out 7, maximum reachable depth 2, and maximum
reachable node count 7. All 1,336 `CALLS` edges had persisted edge evidence.
The `CALLS` domain split was 712 `CODE` and 624 `TEST` edges.

Before this change SQLite had source/type and generic endpoint indexes but no
composite traversal indexes. The schema initializer now creates:

* `idx_analysis_graph_edges_calls_outgoing(source_id, edge_type, status, from_node_id, id)`
* `idx_analysis_graph_edges_calls_incoming(source_id, edge_type, status, to_node_id, id)`

The existing edge-evidence primary key supports lookup by edge ID; the
existing claim/node and current-file indexes support entrypoint and current
membership filtering.

## Implementation choice

At current scale, bounded batched frontier loading is simpler and more
observable than a recursive CTE. One store call loads the current connected
`CALLS` facts for all source-scoped anchors. Pure in-memory traversal then
performs reverse entrypoint discovery, typed identity deduplication/ranking,
and one downstream slice per selected entrypoint. Evidence is hydrated only
after `maxFlows` selection, in two batched queries per returned source (edge
representatives, then supplemental node evidence). There is no query per
node, edge, anchor, entrypoint, or flow.
