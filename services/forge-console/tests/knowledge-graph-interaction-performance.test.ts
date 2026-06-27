import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const operatorPath = (...parts: string[]) => resolve(import.meta.dirname, '..', 'src', 'operator', ...parts);

function functionBody(source: string, name: string): string {
  const start = source.indexOf(`${name}(`) >= 0
    ? source.lastIndexOf('\n', source.indexOf(`${name}(`)) + 1
    : source.indexOf(`function ${name}`);
  expect(start).toBeGreaterThanOrEqual(0);
  const next = source.indexOf('\n  }\n\n  ', start + 1);
  return source.slice(start, next === -1 ? undefined : next);
}

describe('knowledge graph interaction performance contract', () => {
  it('keeps transform-only scheduling out of graph data loading', async () => {
    const source = await readFile(operatorPath('knowledge-graph-page.js'), 'utf8');
    expect(source).toContain('scheduleKnowledgeGraphTransform(reason = \'pan\')');
    expect(source).toContain('requestAnimationFrame');
    expect(source).toContain('if (this.state.transformFrame)');
    expect(source).toContain("svg.setAttribute('viewBox'");
    expect(source).toContain('-transform.x / scale');
    expect(source).toContain('width / scale');
  });

  it('derives minimum zoom from graph bounds so wheel zoom-out can reach fit scale', async () => {
    const source = await readFile(operatorPath('knowledge-graph-page.js'), 'utf8');
    expect(source).toContain('fitKnowledgeGraph()');
    expect(source).toContain('this.recomputeKnowledgeGraphFitZoom()');
    expect(source).toContain('this.state.fitZoom *');
    expect(source).toContain('Math.min(0.18');
  });

  it('exposes runtime metrics that can prove interaction stays local', async () => {
    const source = await readFile(operatorPath('knowledge-graph-page.js'), 'utf8');

    expect(source).toContain('windowRef.__forgeGraphMetrics');
    expect(source).toContain('windowRef.__forgeGraphMetricsReset');
    expect(source).toContain('layoutRunCount');
    expect(source).toContain('dataFetchCount');
    expect(source).toContain('graphModelBuildCount');
    expect(source).toContain('transformOnlyFrameCount');
    expect(source).toContain('panEventCount');
    expect(source).toContain('wheelEventCount');
    expect(source).toContain('fullGraphRebuildCount');
    expect(source).toContain('fullRendererRebuildCount');
    expect(source).toContain('tabRenderCount');
    expect(source).toContain('dataReloadCount');
    expect(source).toContain('labelMeasureCount');
    expect(source).toContain('labelRenderCount');
    expect(source).toContain('lastPanFrameMs');
    expect(source).toContain('lastZoomFrameMs');
  });

  it('stores CI-safe graph interaction budgets in one file', async () => {
    const budgets = JSON.parse(await readFile(resolve(import.meta.dirname, 'performance-budgets.json'), 'utf8'));

    expect(budgets.knowledgeGraphInteraction.nodes400Edges1200.panP95FrameMs).toBe(32);
    expect(budgets.knowledgeGraphInteraction.nodes400Edges1200.zoomP95FrameMs).toBe(32);
    expect(budgets.knowledgeGraphInteraction.nodes1000Edges3000.panP95FrameMs).toBe(50);
    expect(budgets.knowledgeGraphInteraction.nodes1000Edges3000.zoomP95FrameMs).toBe(50);
    expect(budgets.knowledgeGraphInteraction.nodes2000Edges6000.maxMainThreadTaskMs).toBe(500);
    expect(budgets.knowledgeGraphInteraction.nodes2000Edges6000.requiresNoLayoutDuringInteraction).toBe(true);
    expect(budgets.knowledgeGraphInteraction.nodes2000Edges6000.requiresNoDataReloadDuringInteraction).toBe(true);
    expect(budgets.knowledgeGraphInteraction.nodes2000Edges6000.requiresNoFullRebuildDuringInteraction).toBe(true);
  });
});
