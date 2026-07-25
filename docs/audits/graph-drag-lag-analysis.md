# Knowledge Graph Drag Lag Analysis

Date: 2026-06-18

Branch: `feature/SITIONIX-37`

## Scope

This audit covers already-loaded Knowledge graph interaction in Forge Console. The fix intentionally does not change graph visual design, node sizes, edge styles, label policy, layout constants, zoom limits, full graph mode, or backend graph semantics.

## Files Inspected

- `services/forge-console/src/operator/operator-ui.js`
- `services/forge-console/src/operator/operator-ui.css`
- `services/forge-console/src/operator/knowledge-graph.html`
- `services/forge-console/tests/knowledge-graph-interaction-performance.test.ts`
- `services/forge-console/tests/performance-budgets.json`

Search used:

```bash
rg -n "pointermove|mousemove|mousedown|mouseup|wheel|drag|pan|zoom|requestAnimationFrame|setTransform|render|draw|layout|force|simulation|label|measureText|innerHTML|appendChild|replaceChildren|querySelector|selected|hover|nodesTab|edgesTab|graph" services/forge-console
```

## Baseline Findings

The current visual renderer is SVG:

- one `<line>` per edge
- one `<g>` per node
- one `<circle>`, `<text>`, and `<title>` per node
- one `<title>` per edge

Approximate SVG element counts for deterministic graph sizes:

| Fixture | Nodes | Edges | Approx SVG Elements |
| --- | ---: | ---: | ---: |
| Medium | 400 | 1,200 | ~4,000 |
| Large | 1,000 | 3,000 | ~10,000 |
| XL | 2,000 | 6,000 | ~20,000 |

Pan and wheel zoom already avoided graph data reload, graph model rebuild, and layout execution. The confirmed runtime bottleneck was the per-frame SVG viewport transform path:

```js
viewport.setAttribute('transform', `translate(${x}, ${y}) scale(${k})`);
```

With thousands of SVG children, updating the SVG transform attribute on every pan/zoom frame is expensive because the browser must repaint a large SVG subtree containing text, lines, markers, and node drop-shadows. This matches the observed lag on static loaded graphs with 400+ nodes.

Browser FPS numbers were not captured in this pass because the repository does not currently expose a runnable graph Playwright harness for deterministic fixtures. Runtime counters were added so browser/e2e tests can now verify interaction behavior directly.

## Bottleneck Classification

Confirmed:

- D. SVG/DOM paint is too expensive during viewport movement.
- J. High-frequency interaction must remain requestAnimationFrame-batched.
- K. Existing SVG text/filter paint cost amplifies the expensive transform path.

Not confirmed after inspection:

- A. Layout runs during drag/zoom.
- B. Graph model rebuilds during drag/zoom.
- C. Renderer rebuilds all nodes/edges during drag/zoom.
- G. Hit-testing scans all edges on pointer move.
- H/I. Lower panels/tables rerender during pan/zoom.
- L. Graph fetch/cache invalidates during movement.

## Fix

First attempted fix:

```js
viewport.style.transform = `translate(${x}px, ${y}px) scale(${k})`;
```

This was reverted because CSS transform on the SVG `<g>` made the user's browser lag harder. SVG group CSS transforms are not reliably compositor-cheap for this workload.

Current fix:

```js
svg.setAttribute('viewBox', `${-x / k} ${-y / k} ${width / k} ${height / k}`);
```

Pan/zoom now moves the root SVG camera instead of applying a CSS transform to the large child subtree. Node and edge coordinates are still rebuilt only on layout/data changes or explicit node dragging.

## Runtime Metrics

Development/test exposes `window.__forgeGraphMetrics` and `window.__forgeGraphMetricsReset`.

During drag/pan of an already loaded graph, these should not increase:

- `layoutRunCount`
- `dataFetchCount`
- `graphModelBuildCount`
- `fullGraphRebuildCount`
- `fullRendererRebuildCount`
- `tabRenderCount`

These may increase:

- `panEventCount`
- `wheelEventCount`
- `transformOnlyFrameCount`
- `renderFrameCount` only for actual node dragging, not viewport pan

## Visual Behavior

Unchanged:

- node radius and node type sizing
- node colors/strokes/drop-shadows
- edge width, opacity, color, markers
- label font and label visibility behavior
- force layout constants and graph spacing
- fit and minimum zoom calculation
- full graph/max item behavior

## Tests Added/Updated

- `services/forge-console/tests/knowledge-graph-interaction-performance.test.ts`
  - asserts pan remains transform-only and RAF-batched
  - asserts wheel remains transform-only and RAF-batched
  - asserts viewport transform is applied by CSS transform, not SVG transform attribute
  - asserts interaction metrics fields exist
  - asserts 400, 1,000, and 2,000 node interaction budgets are centralized
- `services/forge-console/tests/performance-budgets.json`
  - adds 2,000 node / 6,000 edge no-rebuild/no-reload budget contract

## Commands Run

```bash
cd services/forge-console
npm run typecheck
npm test
npm run build
mvn -q -DskipTests compile
```

Results:

- `npm run typecheck`: pass
- `npm test`: pass, 4 files / 13 tests
- `npm run build`: pass
- `mvn -q -DskipTests compile`: pass
- `npm run test:e2e`: not run because `services/forge-console/package.json` has no `test:e2e` script
