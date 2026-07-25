# Graph Visual Contract Restore

## Baseline

- Current branch: `feature/SITIONIX-37`
- Visual baseline: `main`
- Baseline commit: `18d4c445ba6c8e95adf554fc2e5c69efa8412eb2`
- `git diff --name-status main...HEAD -- services/forge-console`: no committed Console diff; the regression was in the working tree.
- Working tree comparison used: `git diff main -- services/forge-console`

## Files Compared

- `services/forge-console/src/operator/knowledge-graph.html`
- `services/forge-console/src/operator/operator-ui.css`
- `services/forge-console/src/operator/operator-ui.js`
- `services/forge-console/scripts/copy-static.mjs`
- `services/forge-console/src/operator/runtime-config.js`
- `services/forge-console/src/operator/runtime-config.json`

## File Classification

- `operator-ui.js`: `DATA_PIPELINE`, `VISUAL_RENDERER`, `VISUAL_LAYOUT`, `VIEWPORT_INTERACTION`, `DETAILS_TABLES`
- `operator-ui.css`: `VISUAL_STYLE`
- `knowledge-graph.html`: `VISUAL_RENDERER`, `VIEWPORT_INTERACTION`
- `copy-static.mjs`: `DATA_PIPELINE`
- `runtime-config.js`: `DATA_PIPELINE`
- `runtime-config.json`: `DATA_PIPELINE`
- Nexus static regression test: `OTHER`

## Restored From Main

- Restored `knowledgeGraphSvg` as the graph viewport.
- Removed the canvas graph renderer from the Knowledge graph page.
- Restored SVG node and edge DOM rendering:
  - SVG `<line>` edges
  - SVG `<g>` node groups
  - SVG `<circle>` node shapes
  - SVG text labels using `.knowledge-graph-node-label`
- Restored the main force layout:
  - compact density scale `0.54`
  - normal density scale `0.86`
  - spacious density scale `1.08`
  - repulsion `260 / 480 / 720`
  - center force `0.0086 / 0.0062 / 0.0042`
  - `190` layout ticks
  - collision padding `8` compact, `14` otherwise
  - link target `(62 * densityScale) + from.r + to.r`
- Restored main node radius values:
  - `CALLABLE: 19`
  - `TYPE: 22`
  - `FILE: 17`
  - `FIELD: 14`
  - `CONFIG: 16`
  - `RESOURCE: 16`
  - `DATA: 15`
  - `EXTERNAL: 14`
  - default `15`
  - root boost `7`
  - degree boost `min(10, sqrt(degree) * 2.4)`
- Restored main edge, label, selected, confidence, and dimmed styling from CSS.
- Restored main label policy through `knowledgeGraphShouldShowLabel`.

## Removed Visual Regression

- Removed `graph-visual-defaults.js`.
- Removed the canvas draw path for Knowledge graph nodes and edges.
- Removed the worker/radial coordinate generation path from the graph data loader.
- Removed stale canvas state fields from `knowledgeGraphState`.

## Kept From New Pipeline

- Manifest/node/edge snapshot loading for full mode.
- Cursor/page fetch chain.
- request cancellation through `AbortController`.
- IndexedDB graph snapshot cache.
- normalized in-memory graph store.
- bounded lower table rendering.
- Force Refresh control.
- non-passive wheel listener behavior on the graph viewport.

The data loader no longer injects visual layout coordinates. Visual layout is owned by the restored main SVG force renderer. The cache key uses `main-svg-force-v1` so older radial/canvas coordinates are not reused.

## Viewport Behavior

- Wheel over the graph still calls `preventDefault`.
- Wheel over the graph zooms around the pointer.
- Page scroll outside the graph is not globally blocked.
- Fit uses loaded-node bounds and applies an SVG viewport transform.
- Zoom and pan do not recalculate layout.

## Tests Added / Updated

- Added `services/forge-console/tests/knowledge-graph-visual-contract.test.ts`
  - asserts SVG renderer
  - asserts exact main node radius constants
  - asserts exact main force-layout constants
  - asserts exact main edge/label CSS constants
  - asserts radial worker/canvas graph renderer are not used
- Updated `OperatorStaticUiRegressionTest` to expect `knowledgeGraphSvg` and main layout/radius constants.

## Commands Run

- `git status --short`
- `git branch --show-current`
- `git diff --name-status main...HEAD -- services/forge-console`
- `git diff main...HEAD -- services/forge-console`
- `git diff --name-status main -- services/forge-console`
- `git diff main -- services/forge-console/src/operator/operator-ui.js`
- `rg -n "radius|nodeRadius|nodeSize|diameter|edgeWidth|lineWidth|fontSize|label|labels|collision|repel|charge|spacing|force|layout|radial|spiral|circular|circle|zoom|minZoom|fit|sigma|canvas|webgl|graph" services/forge-console`
- `cd services/forge-console && npm run typecheck` - passed
- `cd services/forge-console && npm test` - passed, 3 files / 7 tests
- `cd services/forge-console && npm run build` - passed
- `mvn -q -DskipTests compile` - passed

`services/forge-console` has no `npm run test:e2e` script at this point.
