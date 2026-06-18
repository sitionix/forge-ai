# Graph Runtime Interaction Performance

## Scope

This pass keeps the restored `main` SVG visual contract:

- node radii unchanged
- edge styles unchanged
- label styles and visibility behavior unchanged
- force layout constants unchanged
- SVG renderer unchanged

The work only changes high-frequency interaction scheduling and diagnostics.

## Baseline Measurement

Browser FPS capture was not available in this repo during this pass because `services/forge-console` has no Playwright/e2e script or browser performance harness. The measurement below is from source inspection and instrumentation points added before final verification.

Current renderer:

- renderer: SVG
- graph item DOM model: one SVG line per edge, one SVG group per node, one circle and one label per node
- layout: restored `main` force layout, run inside `runKnowledgeGraphLayout`
- viewport: SVG `<g class="knowledge-graph-viewport">` transform

Estimated active graph DOM nodes:

- SMALL 100 / 300: about 900 graph SVG elements
- MEDIUM 400 / 1,200: about 3,600 graph SVG elements
- LARGE 1,000 / 3,000: about 9,000 graph SVG elements

Pre-change interaction behavior:

- pan: every `pointermove` directly called `applyKnowledgeGraphTransform`
- wheel zoom: already RAF-coalesced, but used direct transform application after zoom math
- node drag: every `pointermove` directly called `renderKnowledgeGraphFrame`
- graph rebuild: wheel listener was attached inside `renderKnowledgeGraphVisual`; repeated graph rebuilds could stack wheel handlers
- layout during pan/zoom: not called
- data reload during pan/zoom: not called
- lower tabs during pan/zoom: not rerendered
- hit testing during pan/zoom: none in the SVG path

## Confirmed Bottleneck

Confirmed bottlenecks:

- `I. Event handlers run more often than frame budget`
- `B. Renderer updates are not fully frame-batched during interaction`

Not confirmed in current SVG path:

- layout recomputation during pan/zoom
- data reload during pan/zoom
- lower panel rerender during pan/zoom
- edge hit testing during pointer move
- radial/spiral layout usage

## Files Changed

- `services/forge-console/src/operator/operator-ui.js`
- `services/forge-console/tests/knowledge-graph-interaction-performance.test.ts`
- `services/forge-console/tests/performance-budgets.json`
- `docs/audits/graph-runtime-interaction-performance.md`

## Render Loop Changes

Pan/zoom now update only local viewport transform state and schedule one RAF commit:

- `moveKnowledgeGraphPointer` updates `knowledgeGraphState.transform`
- pan calls `scheduleKnowledgeGraphTransform('pan')`
- wheel captures event data, coalesces wheel events through RAF, then calls `scheduleKnowledgeGraphTransform('zoom')`
- `applyKnowledgeGraphTransformNow` performs the only DOM write: setting the SVG viewport `transform`

Node dragging is also RAF-batched through `scheduleKnowledgeGraphFrame`, which updates existing SVG edge/node coordinates without rebuilding graph data or lower panels.

## Layout Freeze Behavior

Layout remains frozen after graph render:

- `runKnowledgeGraphLayout` runs during `renderKnowledgeGraphVisual`
- pan does not call layout
- zoom does not call layout
- selection does not call layout
- hover has no graph-wide hit-test path in the current SVG renderer

## Panel Rerender Behavior

Pan/zoom do not call:

- `renderKnowledgeGraphDetails`
- `renderKnowledgeGraphPreview`
- `renderKnowledgeGraphSelectionState`
- `loadKnowledgeGraph`
- `renderKnowledgeGraphVisual`

Selection still rerenders the preview/details as before.

## Metrics

`window.__forgeGraphMetrics` now exposes:

- `layoutRunCount`
- `renderFrameCount`
- `transformOnlyFrameCount`
- `fullGraphRebuildCount`
- `tabRenderCount`
- `hoverHitTestCount`
- `dataReloadCount`
- `lastPanFrameMs`
- `lastZoomFrameMs`
- `longTaskCount`

Expected during pan/zoom of an already-loaded graph:

- `layoutRunCount`: unchanged
- `dataReloadCount`: unchanged
- `fullGraphRebuildCount`: unchanged
- `tabRenderCount`: unchanged
- `transformOnlyFrameCount`: increases at most once per animation frame

## Final Performance Numbers

Browser FPS and p95 frame numbers were not captured in this pass because no browser performance harness exists in `services/forge-console`.

Static/runtime-contract targets added in `services/forge-console/tests/performance-budgets.json`:

- 400 nodes / 1,200 edges: p95 pan and zoom frame <= 32 ms
- 1,000 nodes / 3,000 edges: p95 pan and zoom frame <= 50 ms

## Tests Added

- `services/forge-console/tests/knowledge-graph-interaction-performance.test.ts`
  - pan path is transform-only and RAF-batched
  - wheel zoom path is transform-only and RAF-batched
  - metrics are exposed
  - budgets are loaded from one JSON file

Existing visual contract tests continue to guard the restored SVG/main visual behavior.

## Commands Run

- `rg -n "mousemove|pointermove|wheel|drag|pan|zoom|requestAnimationFrame|setTransform|render|draw|layout|force|simulation|label|innerHTML|appendChild|querySelector|resize|selected|hover|nodesTab|edgesTab|graph" services/forge-console`
- `cd services/forge-console && npm run typecheck` - passed
- `cd services/forge-console && npm test` - passed, 4 files / 11 tests
- `cd services/forge-console && npm run build` - passed
- `mvn -q -DskipTests compile` - passed

`services/forge-console` has no `npm run test:e2e` script.
