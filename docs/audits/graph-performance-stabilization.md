# Graph Performance Stabilization Audit

Date: 2026-06-18

## Baseline Findings

- Renderer/library: custom static operator UI in `services/forge-console/src/operator/operator-ui.js`; no external graph library.
- Rendering mode before stabilization: SVG with one `<line>` per edge and one `<g>`/`<circle>`/`<text>` group per node.
- Layout before stabilization: custom synchronous force/collision loop, 190 ticks, O(n^2) node pair work, executed on the browser main thread during `renderKnowledgeGraphVisual`.
- Full graph endpoint before stabilization: `GET /api/v1/knowledge/analysis/graph`, proxied by Nexus as `GET /api/v1/infrastructure/knowledge/analysis/graph`.
- Full graph request parameters before stabilization: source/filter parameters plus `limit`; Console sent `limit` from the Max control.
- Legacy full graph cap: non-unlimited full graph requests clamped to 500 nodes and 1,000 edges in `AnalysisStore.graph`. That compatibility surface has since been removed from the active API.
- Initial payload before stabilization: nodes and edges could include summary/evidence/diagnostic-related fields depending on request flags. Console already sent `includeEvidence=false` and `includeClaims=false` for graph visual loading.
- Render count during one legacy load: one full SVG rebuild after the fetch, plus detail tab HTML rebuild.
- Layout executions during one legacy load: one synchronous layout per full graph render; resize with preserved positions no longer intentionally reruns layout.
- Lower tabs before stabilization: Nodes and Edges rendered one table row per returned node/edge.
- Main-thread bottleneck: synchronous O(n^2) layout plus SVG DOM creation/update for every node/edge.

The local Python test virtualenv is stale and system Python lacks FastAPI, so browser Performance API timing for 100/1,000/5,000 fixtures was not captured in this run. Static measurement confirms the dominant current bottleneck before changes: main-thread O(n^2) layout and per-item SVG DOM.

## Implemented Architecture

- Added versioned snapshot endpoints in Forge Knowledge:
  - `GET /api/v1/knowledge/analysis/graph/manifest`
  - `GET /api/v1/knowledge/analysis/graph/nodes`
  - `GET /api/v1/knowledge/analysis/graph/edges`
- Removed the previous full-graph and graph-slice endpoints from the active API.
- Snapshot pages use cursor/keyset pagination ordered by graph fact id. No OFFSET pagination is used for snapshot pages.
- Snapshot revision is derived from source/filter identity, node/edge counts, and max graph fact timestamps. It avoids hashing the full graph.
- Cursor tokens include graph revision and page kind. Invalid cursors return `GRAPH_CURSOR_INVALID`; changed revisions return `GRAPH_SNAPSHOT_STALE`.
- Manifest supports `ETag`, `If-None-Match`, `304 Not Modified`, `X-Graph-Revision`, `Cache-Control`, and coarse `Server-Timing`.
- Added Forge Nexus proxy routes for manifest/nodes/edges and preserved graph headers/status.
- Added Console full-mode loader that requests manifest, then every node/edge page until loaded counts reach manifest totals.
- Added normalized frontend graph store maps for nodes, edges, incoming/outgoing edge ids, and pending missing-endpoint edges.
- Added request cancellation with `AbortController`; stale loads are ignored by load token.
- Added IndexedDB snapshot cache keyed by filters, graph revision, projection version, and layout version. Force Refresh bypasses cache reuse.
- Replaced full graph SVG rendering with a batched Canvas renderer. Slice mode still uses the same data path but renders through Canvas.
- Layout now runs via Web Worker when available. The worker produces deterministic radial coordinates and is cancelled/ignored by token on stale loads.
- Pan/zoom use transform-only Canvas redraw. Layout is not rerun on wheel, pan, or selection.
- Wheel listener is non-passive and attached only to the graph canvas container path:
  `canvas.addEventListener("wheel", zoomKnowledgeGraph, { passive: false })`
- Zoom is centered around the pointer and coalesced through `requestAnimationFrame`.
- Fit/min zoom is based on actual graph bounds including node radius and configurable padding.
- Nodes/Edges detail tabs are bounded to `graphTablePageSize` rows.

## Query and Index Changes

Added idempotent SQLite indexes:

- `idx_analysis_graph_nodes_snapshot_page(source_id, flow_domain, id)`
- `idx_analysis_graph_nodes_source_flow_created(source_id, flow_domain, created_at)`
- `idx_analysis_graph_edges_snapshot_page(source_id, flow_domain, id)`
- `idx_analysis_graph_edges_source_flow_created(source_id, flow_domain, created_at)`

Snapshot page queries select only minimal visual node/edge projection fields and do not load claim evidence excerpts.

## Cache Behavior

- Warm load attempts IndexedDB first and renders the last valid cached graph immediately.
- Manifest is then requested with `If-None-Match` when an ETag is cached.
- `304` keeps the cached graph.
- New graph revision loads a full replacement snapshot and persists it atomically after pages and layout complete.
- Cache eviction keeps the newest configured revisions per filter key.

## Compatibility

- Current compatibility is limited to the final graph snapshot manifest, bounded page, and detail endpoints.
- Legacy full graph and slice contracts are removed from the active graph API.
- Nexus forwards only the final snapshot graph endpoints.

## Verification

Passed:

- `python3 -m py_compile services/forge-knowledge/src/knowledge_service/analysis_store.py services/forge-knowledge/src/knowledge_service/main.py`
- `node --check services/forge-console/src/operator/operator-ui.js`
- `node --check services/forge-console/scripts/copy-static.mjs`
- `mvn -pl services/forge-nexus/application,services/forge-nexus/infrastructure/knowledge-client,services/forge-nexus/api-rest -am -DskipTests compile`
- `cd services/forge-console && npm run typecheck`
- `cd services/forge-console && npm test -- --run`
- `cd services/forge-console && npm run build`
- `mvn -pl services/forge-nexus/boot -am -Dtest=OperatorStaticUiRegressionTest -Dsurefire.failIfNoSpecifiedTests=false test`

Could not run:

- Knowledge pytest suite. `services/forge-knowledge/.venv/bin/pytest` points to a removed interpreter path, and system Python lacks `fastapi`.

## Known Remaining Issues

- No Playwright browser performance harness exists yet in this repo; browser-level wheel/cache/frame-budget tests were not added.
- Backend performance budgets are represented by the new deterministic integration test structure, but the test could not be executed in this environment.
- The Canvas renderer uses a deterministic radial worker layout rather than the previous force layout. It is stable and off-main-thread, but visual placement differs from the old SVG force layout.
- Claims/Diagnostics detail tabs remain driven by loaded graph data or selected-item detail API; full server-backed tab pagination is not implemented.
