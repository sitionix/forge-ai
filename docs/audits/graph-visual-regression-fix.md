# Graph Visual Regression Fix

Superseded by `docs/audits/graph-visual-contract-restore.md`. The final fix restores the `main` SVG renderer and force-layout visual contract directly, rather than keeping a separate `graph-visual-defaults.js` visual configuration file.

## What Regressed

The graph performance refactor moved the Knowledge graph from SVG elements to a canvas renderer and added a worker layout. During that change, visual geometry was mixed into performance code:

- node radii and boosts were applied directly as canvas pixel sizes
- edge widths and opacity were hard-coded in the canvas draw path
- the new worker used radial ring placement instead of the prior force/collision density
- label drawing was allowed too broadly at full-graph scale

The result was a faster graph with oversized circles, dense overlap, label flooding, and radial spike-like piles.

## Files Causing the Oversized Visuals

- `services/forge-console/src/operator/operator-ui.js`
  - `knowledgeGraphNodeRadius`
  - `drawKnowledgeGraphNode`
  - `drawKnowledgeGraphEdge`
  - `knowledgeGraphLayoutWorker`
  - `computeKnowledgeGraphRadialLayout`
- `services/forge-console/src/operator/knowledge-graph.html`
  - switched the viewport from SVG to canvas

## Restored Visual Defaults

The defaults are centralized in `services/forge-console/src/operator/graph-visual-defaults.js`.

Current compact node radii:

- `CALLABLE`: 8
- `TYPE`: 10
- `FILE`: 7
- `FIELD`: 6
- `CONFIG`: 7
- `RESOURCE`: 7
- `DATA`: 7
- `EXTERNAL`: 6
- default: 7
- selected/root/degree boosts remain bounded at compact values

Edge defaults restore the old SVG CSS equivalents:

- default edge width: 1.35
- `CALLS` width: 2.1
- `REFERENCES` / `IMPORTS` width: 0.9
- selected edge width: 3.4
- default opacity: 0.36
- `REFERENCES` / `IMPORTS` opacity: 0.22
- dimmed opacity: 0.12

Label defaults:

- font size: 11
- label stroke width: 4.5
- auto-label threshold: 0.7 zoom
- automatic labels capped to 80 nodes
- selected, searched, and root nodes can still show labels

## Layout Behavior

The worker layout now uses the previous force/collision density model instead of the radial ring layout. Collision padding is tied to the actual restored node radius, not a large arbitrary visual size.

Performance behavior is preserved:

- layout still runs in a worker when available
- pan and zoom do not rerun layout
- cached coordinates still skip layout
- canvas rendering remains batched through `requestAnimationFrame`

## Tests Added

- `services/forge-console/tests/knowledge-graph-visual-defaults.test.ts`

The test executes the browser defaults file and fails if default node radius, edge width, opacity, label size, or auto-label counts drift into oversized values again.

## Commands Run

- `git status --short`
- `git diff -- services/forge-console`
- `git diff -- services/forge-nexus`
- `git diff -- services/forge-knowledge`
- `rg -n "radius|nodeSize|nodeRadius|diameter|circle|edgeWidth|lineWidth|fontSize|label|collision|repel|charge|spacing|layout|zoom|minZoom|scale|sigma|cytoscape|canvas|webgl|graph" services/forge-console`
- `cd services/forge-console && npm run typecheck` - passed
- `cd services/forge-console && npm test` - passed, 3 files / 6 tests
- `cd services/forge-console && npm run build` - passed
- `mvn -q -DskipTests compile` - passed

`services/forge-console` does not currently define an `npm run test:e2e` script, so no graph browser screenshot test was run in this pass.

## Before / After Notes

Before:

- canvas graph used visually oversized node circles
- labels could flood the graph at low/full zoom
- edges were visually heavier than intended
- worker layout formed radial piles/spikes

After:

- graph visual geometry is separated from performance configuration
- node circles are compact again
- labels remain bounded at low zoom
- edge styling matches the prior SVG visual hierarchy
- layout density follows the old force/collision behavior while staying off the main thread
