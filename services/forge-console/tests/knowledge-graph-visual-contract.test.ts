import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const operatorPath = (...parts: string[]) => resolve(import.meta.dirname, '..', 'src', 'operator', ...parts);

describe('knowledge graph visual contract', () => {
  it('uses the main SVG renderer instead of the performance canvas renderer', async () => {
    const html = await readFile(operatorPath('knowledge-graph.html'), 'utf8');
    const js = await readFile(operatorPath('operator-ui.js'), 'utf8');

    expect(html).toContain('id="knowledgeGraphSvg"');
    expect(html).not.toContain('knowledgeGraphCanvas');
    expect(html).not.toContain('graph-visual-defaults.js');
    expect(js).toContain("document.getElementById('knowledgeGraphSvg')");
    expect(js).toContain("createSvgElement('circle', { r: node.r })");
    expect(js).toContain("createSvgElement('line'");
    expect(js).not.toContain('getContext(\'2d\')');
    expect(js).not.toContain('drawKnowledgeGraphNode');
    expect(js).not.toContain('computeKnowledgeGraphRadialLayout');
    expect(js).not.toContain('knowledgeGraphLayoutWorker');
  });

  it('keeps exact main node radius and force layout constants', async () => {
    const js = await readFile(operatorPath('operator-ui.js'), 'utf8');

    expect(js).toContain('CALLABLE: 19');
    expect(js).toContain('TYPE: 22');
    expect(js).toContain('FILE: 17');
    expect(js).toContain('FIELD: 14');
    expect(js).toContain('CONFIG: 16');
    expect(js).toContain('RESOURCE: 16');
    expect(js).toContain('DATA: 15');
    expect(js).toContain('EXTERNAL: 14');
    expect(js).toContain('const rootBoost = node.id === knowledgeGraphState.data?.root?.id ? 7 : 0');
    expect(js).toContain('const degreeBoost = Math.min(10, Math.sqrt(Number(node.degree || 0)) * 2.4)');
    expect(js).toContain("const densityScale = density === 'spacious' ? 1.08 : density === 'normal' ? 0.86 : 0.54");
    expect(js).toContain("const repulsion = density === 'spacious' ? 720 : density === 'normal' ? 480 : 260");
    expect(js).toContain("const centerForce = density === 'spacious' ? 0.0042 : density === 'normal' ? 0.0062 : 0.0086");
    expect(js).toContain('for (let tick = 0; tick < 190; tick += 1)');
    expect(js).toContain("const collision = left.r + right.r + (density === 'compact' ? 8 : 14)");
    expect(js).toContain('const target = (62 * densityScale) + edge.fromNode.r + edge.toNode.r');
  });

  it('keeps exact main edge and label styling', async () => {
    const css = await readFile(operatorPath('operator-ui.css'), 'utf8');

    expect(css).toContain('.knowledge-graph-edge {\n  stroke: rgba(255, 248, 222, 0.36);\n  stroke-width: 1.35;');
    expect(css).toContain('.knowledge-graph-edge.edge-calls {\n  stroke: rgba(104, 211, 145, 0.72);\n  stroke-width: 2.1;');
    expect(css).toContain('.knowledge-graph-edge.edge-references,\n.knowledge-graph-edge.edge-imports {\n  opacity: 0.22;\n  stroke-width: 0.9;');
    expect(css).toContain('.knowledge-graph-edge.selected,\n.knowledge-graph-edge.connected {\n  stroke: #f6b84b;\n  stroke-width: 3.4;\n  opacity: 1;');
    expect(css).toContain('.knowledge-graph-node circle {\n  stroke: rgba(255, 248, 222, 0.88);\n  stroke-width: 2;');
    expect(css).toContain('.knowledge-graph-node.selected circle,\n.knowledge-graph-node.search-match circle {\n  stroke: #fff8de;\n  stroke-width: 4;');
    expect(css).toContain('font: 900 11px/1 var(--mono);');
  });
});
