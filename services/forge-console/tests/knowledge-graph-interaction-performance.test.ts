import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const operatorPath = (...parts: string[]) => resolve(import.meta.dirname, '..', 'src', 'operator', ...parts);

async function operatorSource(): Promise<string> {
  return readFile(operatorPath('operator-ui.js'), 'utf8');
}

function functionBody(source: string, name: string): string {
  const start = source.indexOf(`function ${name}`);
  expect(start).toBeGreaterThanOrEqual(0);
  const next = source.indexOf('\n  function ', start + 1);
  return source.slice(start, next === -1 ? undefined : next);
}

describe('knowledge graph interaction performance contract', () => {
  it('keeps pan transform-only and requestAnimationFrame batched', async () => {
    const source = await operatorSource();
    const moveBody = functionBody(source, 'moveKnowledgeGraphPointer');
    const schedulerBody = functionBody(source, 'scheduleKnowledgeGraphTransform');

    expect(moveBody).toContain("scheduleKnowledgeGraphTransform('pan')");
    expect(moveBody).toContain('panEventCount');
    expect(schedulerBody).toContain('requestAnimationFrame');
    expect(schedulerBody).toContain('if (knowledgeGraphState.transformFrame)');
    expect(moveBody).not.toContain('renderKnowledgeGraphDetails');
    expect(moveBody).not.toContain('renderKnowledgeGraphVisual');
    expect(moveBody).not.toContain('runKnowledgeGraphLayout');
    expect(moveBody).not.toContain('loadKnowledgeGraph(');
  });

  it('keeps wheel zoom transform-only and requestAnimationFrame batched', async () => {
    const source = await operatorSource();
    const zoomBody = functionBody(source, 'zoomKnowledgeGraph');
    const applyWheelBody = functionBody(source, 'applyKnowledgeGraphWheelZoom');

    expect(zoomBody).toContain('event.preventDefault()');
    expect(zoomBody).toContain('wheelEventCount');
    expect(zoomBody).toContain('requestAnimationFrame');
    expect(zoomBody).toContain('pendingWheel');
    expect(applyWheelBody).toContain("scheduleKnowledgeGraphTransform('zoom')");
    expect(applyWheelBody).not.toContain('renderKnowledgeGraphDetails');
    expect(applyWheelBody).not.toContain('renderKnowledgeGraphVisual');
    expect(applyWheelBody).not.toContain('runKnowledgeGraphLayout');
    expect(applyWheelBody).not.toContain('loadKnowledgeGraph(');
  });

  it('applies pan and zoom as a single camera viewBox update', async () => {
    const source = await operatorSource();
    const applyTransformBody = functionBody(source, 'applyKnowledgeGraphTransformNow');

    expect(applyTransformBody).toContain("svg.setAttribute('viewBox'");
    expect(applyTransformBody).toContain('-transform.x / scale');
    expect(applyTransformBody).toContain('width / scale');
    expect(applyTransformBody).not.toContain('viewport.style.transform');
    expect(applyTransformBody).not.toContain("setAttribute('transform'");
    expect(applyTransformBody).not.toContain('renderKnowledgeGraphFrame');
    expect(applyTransformBody).not.toContain('renderKnowledgeGraphDetails');
    expect(applyTransformBody).not.toContain('renderKnowledgeGraphVisual');
    expect(applyTransformBody).not.toContain('runKnowledgeGraphLayout');
  });

  it('derives minimum zoom from graph bounds so wheel zoom-out can reach fit scale', async () => {
    const source = await operatorSource();
    const renderBody = functionBody(source, 'renderKnowledgeGraphVisual');
    const fitBody = functionBody(source, 'fitKnowledgeGraph');
    const recomputeBody = functionBody(source, 'recomputeKnowledgeGraphFitZoom');
    const applyWheelBody = functionBody(source, 'applyKnowledgeGraphWheelZoom');

    expect(renderBody).toContain('recomputeKnowledgeGraphFitZoom()');
    expect(fitBody).toContain('recomputeKnowledgeGraphFitZoom()');
    expect(recomputeBody).toContain('knowledgeGraphState.fitZoom * knowledgeGraphPerformanceConfig.fitZoomAllowance');
    expect(recomputeBody).toContain('Math.min(0.18');
    expect(applyWheelBody).toContain('knowledgeGraphState.minimumZoom ?? 0.18');
    expect(applyWheelBody).not.toContain('knowledgeGraphState.minimumZoom || 0.18');
  });

  it('exposes runtime metrics that can prove interaction stays local', async () => {
    const source = await operatorSource();

    expect(source).toContain('window.__forgeGraphMetrics');
    expect(source).toContain('window.__forgeGraphMetricsReset');
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
