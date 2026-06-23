import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { createKnowledgeGraphClient } from '../src/operator/knowledge-graph-client.js';
import { KnowledgeGraphPage, knowledgeGraphNodeRadius } from '../src/operator/knowledge-graph-page.js';

function manifest(revision = 'rev-a') {
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    graphRevision: revision,
    totalNodeCount: 2,
    totalEdgeCount: 1
  };
}

function node(id: string) {
  return { id, nodeKind: 'CALLABLE', label: id, name: id };
}

function graphDom(url = 'http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&flowDomain=CODE') {
  const dom = new JSDOM(`<!doctype html>
    <body data-page="knowledge-graph">
      <button id="refreshKnowledgeGraph"></button>
      <button id="forceRefreshKnowledgeGraph"></button>
      <button id="fitKnowledgeGraph"></button>
      <button id="fitKnowledgeGraphTop"></button>
      <span id="knowledgeGraphUpdated"></span>
      <div id="knowledgeGraphLoading" class="hidden"></div>
      <div id="knowledgeGraphError" class="hidden"></div>
      <h1 id="knowledgeGraphSourceTitle"></h1>
      <p id="knowledgeGraphSubtitle"></p>
      <p id="knowledgeGraphStatusText"></p>
      <select id="knowledgeGraphMode"><option value="overview">Overview</option><option value="full">Full</option><option value="slice">Slice</option></select>
      <select id="knowledgeGraphFlowDomain"><option value="">All</option><option value="CODE">Code</option><option value="CONFIG">Config</option></select>
      <select id="knowledgeGraphDirection"><option value="OUTBOUND">Outbound</option></select>
      <select id="knowledgeGraphDepth"><option value="2">2</option></select>
      <select id="knowledgeGraphExternal"><option value="collapsed">Collapsed</option></select>
      <select id="knowledgeGraphUnresolved"><option value="summarize">Summarize</option><option value="hide">Hide</option></select>
      <select id="knowledgeGraphDensity"><option value="compact">Compact</option></select>
      <select id="knowledgeGraphLabelsMode"><option value="auto">Auto</option></select>
      <select id="knowledgeGraphMaxNodes"><option value="80">80</option></select>
      <select id="knowledgeGraphIsolated"><option value="hide">Hide</option><option value="show">Show</option></select>
      <input id="knowledgeGraphAutoRefresh" type="checkbox">
      <input id="knowledgeGraphSearch">
      <button data-graph-tab="overview"></button>
      <button data-graph-tab="nodes"></button>
      <button data-graph-tab="edges"></button>
      <button data-graph-tab="selected"></button>
      <section id="knowledgeGraphSummary"></section>
      <svg id="knowledgeGraphSvg"></svg>
      <section id="knowledgeGraphDetails"></section>
    </body>`, { url, pretendToBeVisual: true });
  dom.window.requestAnimationFrame = ((callback: FrameRequestCallback) => setTimeout(() => callback(Date.now()), 0)) as unknown as typeof dom.window.requestAnimationFrame;
  dom.window.cancelAnimationFrame = ((id: number) => clearTimeout(id)) as typeof dom.window.cancelAnimationFrame;
  return dom;
}

function response(status: number, body: unknown) {
  return {
    status,
    ok: status >= 200 && status < 300,
    body,
    headers: new Headers()
  };
}

describe('Knowledge graph modular contract', () => {
  it('UI-IT-07 uses final graph APIs only', async () => {
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/manifest')) {
          return response(200, manifest());
        }
        if (path.includes('/nodes')) {
          return Promise.resolve({ graphRevision: 'rev-a', items: [node('n1'), node('n2')], complete: true, returnedCount: 2 });
        }
        if (path.includes('/edges')) {
          return Promise.resolve({ graphRevision: 'rev-a', items: [{ id: 'e1', fromNodeId: 'n1', toNodeId: 'n2', edgeType: 'CALLS' }], complete: true, returnedCount: 1 });
        }
        if (path.includes('/node/n1')) {
          return Promise.resolve({ item: { id: 'n1', evidence: [{ id: 'ev-node' }] } });
        }
        if (path.includes('/edge/e1')) {
          return Promise.resolve({ item: { id: 'e1', evidence: [{ id: 'ev-edge' }] } });
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const client = createKnowledgeGraphClient({ http, config: { graphNodePageSize: 2, graphEdgePageSize: 2 } });
    const query = new URLSearchParams({ sourceId: 'forge-ai', flowDomain: 'CODE', depth: '2' });
    const data = await client.loadSnapshot(query);
    await client.loadNodeDetail('n1', query, data.graphRevision);
    await client.loadEdgeDetail('e1', query, data.graphRevision);

    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/manifest'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/nodes'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/edges'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/node/n1'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/edge/e1'))).toBe(true);
    expect(requests.some((path) => /analysis\/symbols|analysis\/relations|analysis\/graph\/slice|analysis\/graph($|\?)/.test(path))).toBe(false);
  });

  it('UI-IT-03 ignores stale graph responses', async () => {
    const dom = graphDom();
    let resolveSlow: (value: unknown) => void = () => undefined;
    const slow = new Promise((resolve) => {
      resolveSlow = resolve;
    });
    const client = {
      loadSnapshot: vi.fn()
        .mockReturnValueOnce(slow)
        .mockResolvedValueOnce({
          ...manifest('rev-fast'),
          graphRevision: 'rev-fast',
          nodes: [node('fast')],
          edges: [],
          meta: { returnedNodeCount: 1, returnedEdgeCount: 0 }
        }),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client, runtimeConfig: { graphPollIntervalMs: 60000 } });

    const oldRequest = page.loadGraph({ manual: true });
    const newRequest = page.loadGraph({ manual: true });
    await newRequest;
    expect(page.state.data?.graphRevision).toBe('rev-fast');

    resolveSlow({
      ...manifest('rev-slow'),
      graphRevision: 'rev-slow',
      nodes: [node('slow')],
      edges: [],
      meta: { returnedNodeCount: 1, returnedEdgeCount: 0 }
    });
    await oldRequest;
    expect(page.state.data?.graphRevision).toBe('rev-fast');
  });

  it('UI-IT-04 ignores graph mutation after dispose', async () => {
    const dom = graphDom();
    let resolvePending: (value: unknown) => void = () => undefined;
    const pending = new Promise((resolve) => {
      resolvePending = resolve;
    });
    const client = { loadSnapshot: vi.fn(() => pending), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client });
    const request = page.loadGraph({ manual: true });

    page.dispose();
    resolvePending({ ...manifest('rev-disposed'), graphRevision: 'rev-disposed', nodes: [node('n1')], edges: [], meta: {} });
    await request;
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('');
  });

  it('UI-IT-08 resets filters, cursor ownership, and stale details', async () => {
    const dom = graphDom();
    const first = {
      ...manifest('rev-code'),
      graphRevision: 'rev-code',
      nodes: [node('old-node')],
      edges: [],
      meta: { returnedNodeCount: 1, returnedEdgeCount: 0 }
    };
    const second = {
      ...manifest('rev-config'),
      graphRevision: 'rev-config',
      nodes: [node('config-node')],
      edges: [],
      meta: { returnedNodeCount: 1, returnedEdgeCount: 0 }
    };
    let resolveDetail: (value: unknown) => void = () => undefined;
    const detail = new Promise((resolve) => {
      resolveDetail = resolve;
    });
    const client = {
      loadSnapshot: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      loadNodeDetail: vi.fn(() => detail),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client });

    await page.loadGraph({ manual: true });
    void page.selectNode('old-node');
    (dom.window.document.getElementById('knowledgeGraphFlowDomain') as HTMLSelectElement).value = 'CONFIG';
    page.updateUrlFromControls();
    page.resetFilterState();
    resolveDetail({ item: { id: 'old-node', evidence: [{ id: 'old-evidence' }] } });
    await page.loadGraph({ manual: true });

    expect(page.state.selectedNodeId).toBeNull();
    expect(page.state.data?.graphRevision).toBe('rev-config');
    expect(dom.window.document.getElementById('knowledgeGraphDetails')?.textContent).not.toContain('old-evidence');
  });

  it('keeps SVG renderer and exact radius constants', () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadSnapshot: vi.fn() } });
    expect(dom.window.document.getElementById('knowledgeGraphSvg')).toBeTruthy();
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(page.metrics.transformOnlyFrameCount).toBe(0);
  });
});
