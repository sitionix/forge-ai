import { readFile } from 'node:fs/promises';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { resolve } from 'node:path';
import { AddressInfo } from 'node:net';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it } from 'vitest';

const operatorPath = (...parts: string[]) => resolve(import.meta.dirname, '..', 'src', 'operator', ...parts);
const servers: Array<{ close: () => Promise<void> }> = [];

afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

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

  it('restarts failed files through the canonical Nexus analysis endpoint', async () => {
    const html = await readFile(operatorPath('knowledge-graph.html'), 'utf8');
    const js = await readFile(operatorPath('operator-ui.js'), 'utf8');

    expect(html).toContain('id="retryFailedKnowledgeGraph"');
    expect(html).toContain('Restart failed');
    expect(js).toContain("postInfrastructureJson('/knowledge/analysis/build'");
    expect(js).toContain("selection: 'FAILED_ONLY'");
    expect(js).not.toContain("'/api/v1/knowledge/analysis/retry-failed'");
    expect(js).toContain('Restart failed (${failureCount})');
    expect(js).toContain('knowledgeGraphState.retrySubmitting');
  });

  it('starts in overview mode and uses the dedicated graph snapshot endpoints for primary load', async () => {
    const html = await readFile(operatorPath('knowledge-graph.html'), 'utf8');
    const js = await readFile(operatorPath('operator-ui.js'), 'utf8');

    expect(html).toContain('<option value="overview" selected>Overview</option>');
    expect(js).toContain("params.get('mode') || 'overview'");
    expect(js).toContain('loadKnowledgeGraphSnapshot(query');
    expect(js).toContain('/knowledge/analysis/graph/manifest');
    expect(js).toContain('/knowledge/analysis/graph/${kind}');
    expect(js).toContain("pageQuery.set('graphRevision', graphRevision)");
    expect(js).toContain("pageQuery.set('cursor', cursor)");
    expect(js).toContain('knowledgeGraphSnapshotCacheKey(filterKey, graphRevision)');
    expect(js).toContain('isKnowledgeGraphExpiredSnapshotError(error)');
    expect(js).toContain('expiredSnapshotReloaded: true');
    expect(js).toContain('forceRefresh: true');
  });

  it('propagates snapshot detail revisions and does not keep deleted graph clients', async () => {
    const js = await readFile(operatorPath('operator-ui.js'), 'utf8');
    const api = await readFile(resolve(import.meta.dirname, '..', 'src', 'api', 'knowledge-api.ts'), 'utf8');

    expect(js).toContain("query.set('graphRevision', graphRevision)");
    expect(js).toContain('/knowledge/analysis/graph/node/${encodeURIComponent(knowledgeGraphState.selectedNodeId)}?');
    expect(js).toContain('/knowledge/analysis/graph/edge/${encodeURIComponent(knowledgeGraphState.selectedEdgeId)}?');
    expect(api).not.toContain('/knowledge/analysis/graph${query}');
    expect(js).not.toContain('/knowledge/analysis/symbols');
    expect(js).not.toContain('/knowledge/analysis/relations');
    expect(js).not.toContain('/knowledge/analysis/graph/slice?');
  });

  it('IT-GRAPH-17 executes production graph runtime against final snapshot HTTP contract', async () => {
    const requests: string[] = [];
    let expiredManifestServed = false;
    const server = await startKnowledgeGraphServer((request, response) => {
      const url = new URL(request.url || '/', 'http://127.0.0.1');
      requests.push(`${url.pathname}${url.search}`);
      response.setHeader('Content-Type', 'application/json');
      const write = (status: number, body: unknown) => {
        response.statusCode = status;
        response.end(JSON.stringify(body));
      };
      if (url.pathname.endsWith('/manifest') && url.searchParams.get('sourceId') === 'expired') {
        if (!expiredManifestServed) {
          expiredManifestServed = true;
          write(200, manifest('expired', 'rev-expired', 2, 0));
          return;
        }
        write(200, manifest('expired', 'rev-current', 1, 0));
        return;
      }
      if (url.pathname.endsWith('/manifest')) {
        if (url.searchParams.get('sourceId') === 'slow') {
          setTimeout(() => write(200, manifest('slow', 'rev-slow', 1, 0)), 50);
          return;
        }
        const revision = url.searchParams.get('flowDomain') === 'CONFIG' ? 'rev-config' : 'rev-a';
        const nodeCount = revision === 'rev-config' ? 1 : 3;
        write(200, manifest(url.searchParams.get('sourceId') || 'forge-ai', revision, nodeCount, 1));
        return;
      }
      if (url.pathname.endsWith('/nodes') && url.searchParams.get('graphRevision') === 'rev-expired') {
        write(410, { code: 'GRAPH_SNAPSHOT_EXPIRED', message: 'Graph snapshot is no longer retained.' });
        return;
      }
      if (url.pathname.endsWith('/nodes')) {
        const revision = url.searchParams.get('graphRevision');
        if (revision === 'rev-config') {
          if (url.searchParams.has('cursor')) {
            write(400, { code: 'GRAPH_CURSOR_QUERY_MISMATCH', message: 'Config query must not reuse old cursor.' });
            return;
          }
          write(200, { graphRevision: revision, snapshotId: 'snapshot-config', items: [node('config-node')], complete: true, returnedCount: 1 });
          return;
        }
        if (revision === 'rev-current') {
          write(200, { graphRevision: revision, snapshotId: 'snapshot-rev-current', items: [node('current-node')], complete: true, returnedCount: 1 });
          return;
        }
        if (url.searchParams.get('cursor') === 'cursor-n2') {
          write(200, { graphRevision: revision, snapshotId: 'snapshot-a', items: [node('n3')], complete: true, returnedCount: 1 });
          return;
        }
        write(200, { graphRevision: revision, snapshotId: 'snapshot-a', items: [node('n1'), node('n2')], nextCursor: 'cursor-n2', complete: false, returnedCount: 2 });
        return;
      }
      if (url.pathname.endsWith('/edges')) {
        if (url.searchParams.get('graphRevision') === 'rev-config') {
          write(200, {
            graphRevision: 'rev-config',
            snapshotId: 'snapshot-config',
            items: [{ id: 'config-edge', fromNodeId: 'config-node', toNodeId: 'config-node', edgeType: 'DECLARES' }],
            complete: true,
            returnedCount: 1
          });
          return;
        }
        write(200, {
          graphRevision: url.searchParams.get('graphRevision'),
          snapshotId: 'snapshot-a',
          items: [{ id: 'e1', fromNodeId: 'n1', toNodeId: 'n2', edgeType: 'CALLS' }],
          complete: true,
          returnedCount: 1
        });
        return;
      }
      if (url.pathname.endsWith('/node/n1')) {
        write(200, { graphRevision: url.searchParams.get('graphRevision'), snapshotId: 'snapshot-a', item: { id: 'n1', evidence: [{ id: 'ev-a' }] } });
        return;
      }
      if (url.pathname.endsWith('/node/missing')) {
        write(404, { code: 'GRAPH_NODE_NOT_FOUND', message: 'Graph node was not found.' });
        return;
      }
      if (url.pathname.endsWith('/edge/e1')) {
        write(200, { graphRevision: url.searchParams.get('graphRevision'), snapshotId: 'snapshot-a', item: { id: 'e1', evidence: [{ id: 'ev-edge' }] } });
        return;
      }
      write(404, { code: 'NOT_FOUND', message: 'not found' });
    });
    servers.push(server);

    const runtime = await loadOperatorGraphRuntime(server.baseUrl);
    const query = new URLSearchParams({ sourceId: 'forge-ai', flowDomain: 'CODE', depth: '2' });
    const data = await runtime.loadSnapshot(query, {});
    runtime.state.data = data;
    runtime.state.manifest = { graphRevision: data.graphRevision };
    runtime.state.selectedNodeId = 'n1';
    await runtime.loadSelectedDetails();
    runtime.state.selectedNodeId = null;
    runtime.state.selectedEdgeId = 'e1';
    await runtime.loadSelectedDetails();
    runtime.state.selectedNodeId = 'missing';
    runtime.state.selectedEdgeId = null;
    await runtime.loadSelectedDetails();
    const configData = await runtime.loadSnapshot(new URLSearchParams({ sourceId: 'forge-ai', flowDomain: 'CONFIG' }), {});
    const abortController = new AbortController();
    const aborted = runtime.loadSnapshot(new URLSearchParams({ sourceId: 'slow', flowDomain: 'CODE' }), { signal: abortController.signal })
      .then(() => 'resolved')
      .catch((error: Error) => error.name);
    abortController.abort();
    const expiredData = await runtime.loadSnapshot(new URLSearchParams({ sourceId: 'expired', flowDomain: 'CODE' }), {});
    expect(await aborted).toBe('AbortError');
    runtime.dispose();

    expect(data.graphRevision).toBe('rev-a');
    expect(data.nodes.map((item: { id: string }) => item.id)).toEqual(['n1', 'n2', 'n3']);
    expect(data.nodes.every((item: { evidence?: unknown }) => item.evidence === undefined)).toBe(true);
    expect(data.edges).toHaveLength(1);
    expect(configData.graphRevision).toBe('rev-config');
    expect(configData.nodes.map((item: { id: string }) => item.id)).toEqual(['config-node']);
    expect(runtime.state.selectedDetail?.edge?.id).toBe('e1');
    expect(runtime.state.selectedDetail?.evidence?.[0]?.id).toBe('ev-edge');
    expect(runtime.state.selectedDetailError?.code).toBe('GRAPH_NODE_NOT_FOUND');
    expect(expiredData.graphRevision).toBe('rev-current');
    expect(requests).toContain('/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE');
    expect(requests).toContain('/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=rev-a&pageSize=2');
    expect(requests).toContain('/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=rev-a&pageSize=2&cursor=cursor-n2');
    expect(requests).toContain('/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision=rev-a&pageSize=2');
    expect(requests).toContain('/knowledge/analysis/graph/node/n1?sourceId=forge-ai&graphRevision=rev-a&includeEvidence=true');
    expect(requests).toContain('/knowledge/analysis/graph/edge/e1?sourceId=forge-ai&graphRevision=rev-a&includeEvidence=true');
    expect(requests).toContain('/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CONFIG');
    expect(requests).toContain('/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CONFIG&graphRevision=rev-config&pageSize=2');
    expect(requests.some((path) => path.includes('flowDomain=CONFIG') && path.includes('cursor=cursor-n2'))).toBe(false);
    expect(requests.some((path) => path.includes('/analysis/symbols') || path.includes('/analysis/relations') || path.includes('/analysis/graph/slice'))).toBe(false);
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

function manifest(sourceId: string, graphRevision: string, totalNodeCount: number, totalEdgeCount: number) {
  return {
    sourceId,
    snapshotId: `snapshot-${graphRevision}`,
    graphRevision,
    totalNodeCount,
    totalEdgeCount,
    filters: { flowDomain: 'CODE' }
  };
}

function node(id: string) {
  return { id, nodeKind: 'CALLABLE', name: id, label: id };
}

async function startKnowledgeGraphServer(handler: (request: IncomingMessage, response: ServerResponse) => void) {
  const server = createServer(handler);
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  const address = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: () => new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  };
}

async function loadOperatorGraphRuntime(baseUrl: string) {
  const script = await readFile(operatorPath('operator-ui.js'), 'utf8');
  const dom = new JSDOM('<!doctype html><body data-page="test"><div id="knowledgeGraphLoading" class="hidden"></div></body>', {
    url: 'http://127.0.0.1/operator/knowledge-graph.html',
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });
  Object.assign(dom.window, {
    FORGE_OPERATOR_RUNTIME_CONFIG: {
      infrastructureApiBasePath: baseUrl,
      graphCacheEnabled: false,
      graphNodePageSize: 2,
      graphEdgePageSize: 2
    },
    fetch,
    requestAnimationFrame: (callback: FrameRequestCallback) => setTimeout(() => callback(Date.now()), 0),
    cancelAnimationFrame: (id: number) => clearTimeout(id)
  });
  dom.window.eval(script);
  return (dom.window as unknown as { __forgeKnowledgeGraphRuntime: any }).__forgeKnowledgeGraphRuntime;
}
