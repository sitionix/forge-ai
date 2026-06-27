import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { createKnowledgeGraphClient, graphSnapshotQuery } from '../src/operator/knowledge-graph-client.js';
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

function graphData(revision = 'rev-a') {
  return {
    ...manifest(revision),
    graphRevision: revision,
    nodes: [node('n1'), { ...node('n2'), nodeKind: 'TYPE' }],
    edges: [{ id: 'e1', from: 'n1', to: 'n2', edgeType: 'CALLS' }],
    meta: { returnedNodeCount: 2, returnedEdgeCount: 1, totalNodeCount: 2, totalEdgeCount: 1 },
    status: { analysisStatus: 'COMPLETED' }
  };
}

function graphView(revision = 'rev-a') {
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    snapshotId: 'snapshot-a',
    graphRevision: revision,
    queryFingerprint: `fingerprint-${revision}`,
    selectionPolicy: 'RELATIONSHIP_AWARE',
    maxNodes: 80,
    nodes: [node('n1'), { ...node('n2'), nodeKind: 'TYPE' }],
    edges: [{ id: 'e1', fromNodeId: 'n1', toNodeId: 'n2', edgeType: 'CALLS' }],
    totalMatchingNodeCount: 2,
    totalMatchingEdgeCount: 1,
    visibleNodeCount: 2,
    visibleEdgeCount: 1,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    hiddenBoundaryEdgeCount: 0,
    internalEdgeCount: 1,
    hasMore: false,
    status: { analysisStatus: 'COMPLETED' }
  };
}

function contractMetadataPayload(graphAvailable = true) {
  return {
    ...metadataPayload(84, 'COMPLETED', graphAvailable),
    sourceId: 'app-afesox-contracts',
    sourceName: 'API Contracts',
    source: { sourceId: 'app-afesox-contracts', displayName: 'API Contracts', group: 'api', path: 'contracts/openapi.yaml', rootExists: true },
    snapshotId: graphAvailable ? 'contracts-snapshot' : null,
    graphRevision: graphAvailable ? 'contracts-rev' : null
  };
}

function contractGraphView(revision = 'contracts-rev', overrides: Record<string, unknown> = {}) {
  return {
    ...graphView(revision),
    sourceId: 'app-afesox-contracts',
    sourceName: 'API Contracts',
    snapshotId: 'contracts-snapshot',
    graphRevision: revision,
    queryFingerprint: `contracts-${revision}`,
    nodes: [node('contract-node')],
    edges: [],
    totalMatchingNodeCount: 1,
    totalMatchingEdgeCount: 0,
    visibleNodeCount: 1,
    visibleEdgeCount: 0,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    hiddenBoundaryEdgeCount: 0,
    internalEdgeCount: 0,
    hasMore: false,
    ...overrides
  };
}

function graphDom(url = 'http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&flowDomain=CODE') {
  const dom = new JSDOM(`<!doctype html>
    <body data-page="knowledge-graph">
      <button id="refreshKnowledgeGraph"></button>
      <button id="forceRefreshKnowledgeGraph"></button>
      <button id="fitKnowledgeGraph"></button>
      <button id="fitKnowledgeGraphTop"></button>
      <button id="focusKnowledgeGraph"></button>
      <button id="toggleKnowledgeGraphPanel"></button>
      <button id="showKnowledgeGraphEntrypoints"></button>
      <button id="showKnowledgeGraphFull"></button>
      <span id="knowledgeGraphUpdated"></span>
      <div id="knowledgeGraphLoading" class="hidden"></div>
      <div id="knowledgeGraphMetadataError" class="hidden"></div>
      <div id="knowledgeGraphError" class="hidden"></div>
      <div id="knowledgeGraphProgress"></div>
      <h1 id="knowledgeGraphSourceTitle"></h1>
      <p id="knowledgeGraphSubtitle"></p>
      <p id="knowledgeGraphStatusText"></p>
      <select id="knowledgeGraphMode"><option value="slice">Compact slice</option><option value="full">Full overview</option></select>
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
      <div id="knowledgeGraphTruncated" class="knowledge-graph-warning hidden"></div>
      <div id="knowledgeGraphLayout" class="knowledge-graph-layout preview-collapsed">
        <div id="knowledgeGraphStage" class="knowledge-graph-stage">
          <svg id="knowledgeGraphSvg"></svg>
          <div id="knowledgeGraphEmptyAction" class="knowledge-graph-empty-action hidden"><strong></strong><span></span></div>
        </div>
        <aside id="knowledgeGraphPreview" class="knowledge-graph-preview"></aside>
      </div>
      <div id="knowledgeGraphLegend"></div>
      <section id="knowledgeGraphDetails"></section>
    </body>`, { url, pretendToBeVisual: true });
  dom.window.requestAnimationFrame = ((callback: FrameRequestCallback) => setTimeout(() => callback(Date.now()), 0)) as unknown as typeof dom.window.requestAnimationFrame;
  dom.window.cancelAnimationFrame = ((id: number) => clearTimeout(id)) as typeof dom.window.cancelAnimationFrame;
  if (!dom.window.PointerEvent) {
    dom.window.PointerEvent = dom.window.MouseEvent as unknown as typeof dom.window.PointerEvent;
  }
  const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;
  Object.defineProperty(svg, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ left: 0, top: 0, width: 840, height: 600, right: 840, bottom: 600, x: 0, y: 0, toJSON: () => ({}) })
  });
  const stage = dom.window.document.getElementById('knowledgeGraphStage') as HTMLElement;
  Object.defineProperty(stage, 'clientWidth', { configurable: true, value: 840 });
  Object.defineProperty(stage, 'clientHeight', { configurable: true, value: 600 });
  return dom;
}

async function applyOperatorStyles(dom: JSDOM) {
  const css = await readFile('src/operator/operator-ui.css', 'utf8');
  const style = dom.window.document.createElement('style');
  style.textContent = css;
  dom.window.document.head.appendChild(style);
}

function metadataPayload(processed = 7, status = 'COMPLETED', graphAvailable = true) {
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    source: { sourceId: 'forge-ai', displayName: 'Forge AI', group: 'local', rootExists: true },
    inventory: { status: 'READY', fileCount: 10, skippedCount: 0 },
    analysis: { status, totalFiles: 10, processedFiles: processed, failedFiles: 0, pendingFiles: 10 - processed, percent: processed * 10 },
    graphAvailable,
    snapshotId: graphAvailable ? 'snapshot-a' : null,
    graphRevision: graphAvailable ? 'rev-a' : null,
    lastAnalyzedAt: '2026-06-23T10:00:00.000Z',
    lastGraphPublishedAt: graphAvailable ? '2026-06-23T10:01:00.000Z' : null,
    diagnostics: { total: 1, errors: 0, warnings: 1 }
  };
}

function response(status: number, body: unknown) {
  return {
    status,
    ok: status >= 200 && status < 300,
    body,
    headers: new Headers()
  };
}

async function flushAsync(turns = 6) {
  for (let index = 0; index < turns; index += 1) {
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function graphProgress(dom: JSDOM) {
  const document = dom.window.document;
  return {
    target: document.getElementById('knowledgeGraphProgress') as HTMLElement,
    container: document.querySelector('.knowledge-graph-progress') as HTMLElement,
    main: document.querySelector('.knowledge-graph-progress-main') as HTMLElement,
    meta: document.querySelector('.knowledge-progress-meta') as HTMLElement,
    track: document.querySelector('.knowledge-progress-track') as HTMLElement,
    fill: document.querySelector('.knowledge-progress-track span') as HTMLElement,
    metrics: [...document.querySelectorAll('.knowledge-graph-metric')] as HTMLElement[]
  };
}

describe('Knowledge graph modular contract', () => {
  it('UI-GRAPH-PARITY-07 / UI-IT-07 uses final graph APIs only', async () => {
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/view')) {
          return Promise.resolve(graphView());
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

    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/view'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/node/n1'))).toBe(true);
    expect(requests.some((path) => path.includes('/knowledge/analysis/graph/edge/e1'))).toBe(true);
    expect(requests.some((path) => /analysis\/symbols|analysis\/relations|analysis\/graph\/slice|analysis\/graph($|\?)/.test(path))).toBe(false);
  });

  it('UI-GRAPH-PARITY-09 / UI-IT-03 ignores stale graph responses', async () => {
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

  it('UI-GRAPH-PARITY-08 loads the default graph with final manifest parameters', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphView());
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { graphNodePageSize: 2, graphEdgePageSize: 2, graphPollIntervalMs: 60000 }
    });

    page.mount();
    await flushAsync();

    const metadataRequest = requests.find((path) => path.includes('/knowledge/analysis/graph/metadata'));
    expect(metadataRequest).toBeTruthy();
    expect(new URL(metadataRequest || '', 'http://127.0.0.1').searchParams.get('sourceId')).toBe('forge-ai');
    const viewRequest = requests.find((path) => path.includes('/knowledge/analysis/graph/view'));
    expect(viewRequest).toBeTruthy();
    const viewUrl = new URL(viewRequest || '', 'http://127.0.0.1');
    expect(viewUrl.searchParams.get('sourceId')).toBe('forge-ai');
    expect(viewUrl.searchParams.get('flowDomain')).toBe('CODE');
    expect(viewUrl.searchParams.get('includeExternal')).toBe('show');
    expect(viewUrl.searchParams.get('includeUnresolved')).toBe('true');
    expect(viewUrl.searchParams.get('includeIsolated')).toBe('false');
    expect(viewUrl.searchParams.get('maxNodes')).toBe('80');
    expect(viewUrl.searchParams.has('depth')).toBe(false);
    expect(viewUrl.searchParams.has('direction')).toBe(false);
    expect(viewUrl.searchParams.get('includeExternal')).not.toBe('collapsed');
    expect(requests.some((path) => /analysis\/graph\/slice|analysis\/symbols|analysis\/relations/.test(path))).toBe(false);
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toBe('');
    page.dispose();
  });

  it('UI-GRAPH-PARITY-08 classifies graph controls against the final API contract', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&flowDomain=CODE&mode=full&direction=INBOUND&depth=4&density=comfortable&labels=always&includeExternal=hide&unresolved=hide&isolated=show&maxNodes=200&search=handler');
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve({ ...graphView(), nodes: [node('n1')], edges: [], visibleNodeCount: 1, visibleEdgeCount: 0, totalMatchingNodeCount: 1, totalMatchingEdgeCount: 0 });
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { graphNodePageSize: 2, graphEdgePageSize: 2, graphPollIntervalMs: 60000 }
    });

    page.mount();
    await flushAsync();

    const viewRequest = requests.find((path) => path.includes('/knowledge/analysis/graph/view'));
    const viewUrl = new URL(viewRequest || '', 'http://127.0.0.1');
    expect(Object.fromEntries(viewUrl.searchParams.entries())).toMatchObject({
      sourceId: 'forge-ai',
      flowDomain: 'CODE',
      includeExternal: 'hide',
      includeUnresolved: 'false',
      includeIsolated: 'true',
      search: 'handler',
      maxNodes: '200'
    });
    ['mode', 'direction', 'depth', 'density', 'labels', 'max', 'rootGraphNodeId'].forEach((param) => {
      expect(viewUrl.searchParams.has(param)).toBe(false);
    });
    expect(graphSnapshotQuery(new URLSearchParams({
      sourceId: 'forge-ai',
      flowDomain: 'CODE',
      direction: 'INBOUND',
      depth: '4',
      maxNodes: '200',
      includeExternal: 'collapsed'
    })).toString()).toBe('sourceId=forge-ai&flowDomain=CODE&includeExternal=show');
    page.dispose();
  });

  it('UI-GRAPH-PARITY-05 renders metadata independently when graph data fails', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(metadataPayload(4, 'PARTIAL'));
        }
        if (path.includes('/view')) {
          return Promise.reject(Object.assign(new Error('GRAPH_FILTER_INVALID'), { code: 'GRAPH_FILTER_INVALID', status: 400 }));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { graphPollIntervalMs: 60000 }
    });

    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('Forge AI');
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('PARTIAL');
    expect(dom.window.document.getElementById('knowledgeGraphUpdated')?.textContent).not.toBe('loading');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toContain('GRAPH_FILTER_INVALID');

    dom.window.document.getElementById('refreshKnowledgeGraph')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    expect(http.get).toHaveBeenCalledWith('/knowledge/analysis/graph/metadata?sourceId=forge-ai', expect.any(Object));
    expect(http.get.mock.calls.filter(([path]) => path.includes('/knowledge/analysis/graph/metadata'))).toHaveLength(1);
    page.dispose();
  });

  it('UI-GRAPH-STALE-01 opening contract source does not send stale revision', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=app-afesox-contracts&flowDomain=CODE&graphRevision=old-rev&cursor=old-cursor');
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(contractMetadataPayload());
        }
        if (path.includes('/knowledge/analysis/graph/view')) {
          return Promise.resolve(contractGraphView());
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();

    const metadataRequest = requests.find((path) => path.includes('/knowledge/analysis/graph/metadata'));
    const viewRequest = requests.find((path) => path.includes('/knowledge/analysis/graph/view'));
    expect(new URL(metadataRequest || '', 'http://127.0.0.1').searchParams.get('sourceId')).toBe('app-afesox-contracts');
    const viewUrl = new URL(viewRequest || '', 'http://127.0.0.1');
    expect(viewUrl.searchParams.get('sourceId')).toBe('app-afesox-contracts');
    expect(viewUrl.searchParams.has('graphRevision')).toBe(false);
    expect(viewUrl.searchParams.has('cursor')).toBe(false);
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).not.toContain('GRAPH_SNAPSHOT_STALE');
    page.dispose();
  });

  it('UI-GRAPH-STALE-02 source switch resets graph state', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&flowDomain=CODE');
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        const url = new URL(path, 'http://127.0.0.1');
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(url.searchParams.get('sourceId') === 'app-afesox-contracts'
            ? contractMetadataPayload()
            : metadataPayload());
        }
        if (path.includes('/knowledge/analysis/graph/view')) {
          return Promise.resolve(url.searchParams.get('sourceId') === 'app-afesox-contracts'
            ? contractGraphView('contracts-switched')
            : graphView('source-a-rev'));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();
    dom.window.history.replaceState(null, '', '/operator/knowledge-graph.html?sourceId=app-afesox-contracts&flowDomain=CODE&graphRevision=source-a-rev&cursor=source-a-cursor');
    page.resetFilterState();
    await page.loadMetadata({ manual: true });
    await page.loadGraph({ manual: true });

    const contractViewRequests = requests.filter((path) => path.includes('/knowledge/analysis/graph/view') && path.includes('sourceId=app-afesox-contracts'));
    expect(contractViewRequests).toHaveLength(1);
    const contractViewUrl = new URL(contractViewRequests[0] as string, 'http://127.0.0.1');
    expect(contractViewUrl.searchParams.has('graphRevision')).toBe(false);
    expect(contractViewUrl.searchParams.has('cursor')).toBe(false);
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('API Contracts');
    expect(page.state.data?.sourceId).toBe('app-afesox-contracts');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toBe('');
    page.dispose();
  });

  it('UI-GRAPH-STALE-03 real stale graph error is graph-only and retry clears state', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=app-afesox-contracts&flowDomain=CODE&graphRevision=old-rev');
    const requests: string[] = [];
    let viewCalls = 0;
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(contractMetadataPayload());
        }
        if (path.includes('/knowledge/analysis/graph/view')) {
          viewCalls += 1;
          if (viewCalls === 1) {
            return Promise.reject(Object.assign(new Error('GRAPH_SNAPSHOT_STALE'), { code: 'GRAPH_SNAPSHOT_STALE', status: 409 }));
          }
          return Promise.resolve(contractGraphView('contracts-retry'));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('API Contracts');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toContain('GRAPH_SNAPSHOT_STALE');

    dom.window.document.getElementById('refreshKnowledgeGraph')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();

    expect(viewCalls).toBe(2);
    const retryViewRequests = requests.filter((path) => path.includes('/knowledge/analysis/graph/view'));
    const retryViewUrl = new URL(retryViewRequests[retryViewRequests.length - 1] || '', 'http://127.0.0.1');
    expect(retryViewUrl.searchParams.has('graphRevision')).toBe(false);
    expect(retryViewUrl.searchParams.has('cursor')).toBe(false);
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toBe('');
    expect(page.state.data?.graphRevision).toBe('contracts-retry');
    page.dispose();
  });

  it('UI-GRAPH-STALE-04 empty/no-edge graph renders controlled state', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=app-afesox-contracts&flowDomain=CODE');
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(contractMetadataPayload());
        }
        if (path.includes('/knowledge/analysis/graph/view')) {
          return Promise.resolve(contractGraphView('contracts-empty'));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('API Contracts');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toBe('');
    expect((page.state.nodes as Array<{ id: string }>).map((item) => item.id)).toEqual(['contract-node']);
    expect(page.state.edges).toEqual([]);
    expect(dom.window.document.querySelectorAll('.knowledge-graph-node')).toHaveLength(1);
    page.dispose();
  });

  it('UI-GRAPH-STALE-05 filter changes do not reuse stale state', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=app-afesox-contracts&flowDomain=CODE&graphRevision=old-rev&cursor=old-cursor');
    const requests: string[] = [];
    let resolveOldView: (value: unknown) => void = () => undefined;
    const oldView = new Promise((resolve) => {
      resolveOldView = resolve;
    });
    let viewCalls = 0;
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve(contractMetadataPayload());
        }
        if (path.includes('/knowledge/analysis/graph/view')) {
          viewCalls += 1;
          return viewCalls === 1
            ? oldView
            : Promise.resolve(contractGraphView('contracts-filtered', {
              nodes: [node('filtered-contract-node')],
              visibleNodeCount: 1,
              totalMatchingNodeCount: 1
            }));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync(2);
    (dom.window.document.getElementById('knowledgeGraphSearch') as HTMLInputElement).value = 'contract';
    page.updateUrlFromControls();
    page.resetFilterState();
    await page.loadGraph({ manual: true });
    resolveOldView(contractGraphView('contracts-old', { nodes: [node('old-contract-node')] }));
    await flushAsync();

    expect(page.state.data?.graphRevision).toBe('contracts-filtered');
    expect((page.state.nodes as Array<{ id: string }>).map((item) => item.id)).toEqual(['filtered-contract-node']);
    const contractViewRequests = requests.filter((path) => path.includes('/knowledge/analysis/graph/view'));
    expect(contractViewRequests).toHaveLength(2);
    contractViewRequests.forEach((path) => {
      const url = new URL(path, 'http://127.0.0.1');
      expect(url.searchParams.get('sourceId')).toBe('app-afesox-contracts');
      expect(url.searchParams.has('graphRevision')).toBe(false);
      expect(url.searchParams.has('cursor')).toBe(false);
    });
    expect(new URL(contractViewRequests[1] as string, 'http://127.0.0.1').searchParams.get('search')).toBe('contract');
    page.dispose();
  });

  it('UI-GRAPH-PROGRESS-01 renders completed metadata progress without graph view', async () => {
    const dom = graphDom();
    let resolveView: (value: unknown) => void = () => undefined;
    const viewWait = new Promise((resolve) => {
      resolveView = resolve;
    });
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve({
            status: 'COMPLETED',
            processedFileCount: 387,
            fileCount: 387,
            graphAvailable: true,
            sourceId: 'forge-ai',
            sourceName: 'AUTOMATION SERVICE SOX'
          });
        }
        if (path.includes('/view')) {
          return viewWait;
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync(2);

    const progress = graphProgress(dom);
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('AUTOMATION SERVICE SOX');
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toBe('COMPLETED · 387 / 387 files · graph available');
    expect(progress.meta.textContent).toContain('387 / 387 files');
    expect(progress.fill.style.width).toBe('100%');
    expect(progress.container).toBeTruthy();
    expect(http.get.mock.calls.some(([path]) => path.includes('/view'))).toBe(true);

    resolveView(Promise.reject(Object.assign(new Error('GRAPH_FAILED'), { code: 'GRAPH_FAILED' })));
    await flushAsync();
    expect(graphProgress(dom).fill.style.width).toBe('100%');
    page.dispose();
  });

  it('UI-GRAPH-PROGRESS-02 updates running progress from consecutive metadata responses', async () => {
    const dom = graphDom();
    const http = { get: vi.fn(() => Promise.resolve({ status: 'RUNNING', processedFileCount: 37, fileCount: 100 })) };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
    });

    await page.loadMetadata({ manual: true });
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('RUNNING · 37 / 100 files');
    expect(graphProgress(dom).fill.style.width).toBe('37%');

    http.get = vi.fn(() => Promise.resolve({ status: 'RUNNING', processedFileCount: 64, fileCount: 100 }));
    await page.loadMetadata({ manual: true });
    expect(graphProgress(dom).fill.style.width).toBe('64%');
  });

  it('UI-GRAPH-PROGRESS-03 keeps failed files separate from processed progress', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn(() => Promise.resolve({
        status: 'PARTIAL',
        processedFileCount: 84,
        fileCount: 84,
        failedFileCount: 2
      }))
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
    });

    await page.loadMetadata({ manual: true });

    const progress = graphProgress(dom);
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('PARTIAL · 84 / 84 files');
    expect(progress.fill.style.width).toBe('100%');
    expect(progress.metrics.map((metric) => metric.textContent).join(' ')).toContain('Failed Files');
    expect(progress.metrics.map((metric) => metric.textContent).join(' ')).toContain('2');
    expect(progress.meta.textContent).not.toContain('97');
  });

  it('UI-GRAPH-PROGRESS-04 clamps zero, invalid, and out-of-range values', async () => {
    const cases = [
      [{ status: 'RUNNING', processedFileCount: 0, fileCount: 0 }, '0%'],
      [{ status: 'RUNNING', processedFileCount: -5, fileCount: 100 }, '0%'],
      [{ status: 'RUNNING', processedFileCount: 150, fileCount: 100 }, '100%'],
      [{ status: 'RUNNING' }, '0%']
    ] as const;

    for (const [metadata, width] of cases) {
      const dom = graphDom();
      const page = new KnowledgeGraphPage({
        document: dom.window.document,
        window: dom.window,
        http: { get: vi.fn(() => Promise.resolve(metadata)) },
        client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
      });
      await page.loadMetadata({ manual: true });
      expect(graphProgress(dom).fill.style.width).toBe(width);
      expect(graphProgress(dom).target.innerHTML).not.toMatch(/NaN|Infinity/);
    }
  });

  it('UI-GRAPH-PROGRESS-05 keeps metadata progress visible when graph view fails', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.resolve({ status: 'COMPLETED', processedFileCount: 10, fileCount: 20, graphAvailable: true });
        }
        if (path.includes('/view')) {
          return Promise.reject(Object.assign(new Error('GRAPH_DOWN'), { code: 'GRAPH_DOWN' }));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();

    expect(graphProgress(dom).fill.style.width).toBe('50%');
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('COMPLETED');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toContain('GRAPH_DOWN');
    expect(dom.window.document.getElementById('knowledgeGraphUpdated')?.textContent).not.toContain('loading');
    page.dispose();
  });

  it('UI-GRAPH-PROGRESS-06 ignores stale metadata responses after source changes', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=a&flowDomain=CODE');
    let resolveA: (value: unknown) => void = () => undefined;
    const metadataA = new Promise((resolve) => {
      resolveA = resolve;
    });
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('sourceId=a')) {
          return metadataA;
        }
        if (path.includes('sourceId=b')) {
          return Promise.resolve({ sourceId: 'b', sourceName: 'Source B', status: 'RUNNING', processedFileCount: 9, fileCount: 10 });
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
    });

    const requestA = page.loadMetadata({ manual: true });
    expect(graphProgress(dom).target.innerHTML).toBe('');
    dom.window.history.replaceState(null, '', '/operator/knowledge-graph.html?sourceId=b&flowDomain=CODE');
    const requestB = page.loadMetadata({ manual: true });
    await requestB;
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('Source B');
    expect(graphProgress(dom).fill.style.width).toBe('90%');

    resolveA({ sourceId: 'a', sourceName: 'Source A', status: 'COMPLETED', processedFileCount: 100, fileCount: 100 });
    await requestA;
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('Source B');
    expect(graphProgress(dom).fill.style.width).toBe('90%');
  });

  it('UI-GRAPH-PROGRESS-07 matches the main progress DOM and CSS contract at runtime', async () => {
    const dom = graphDom();
    await applyOperatorStyles(dom);
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http: { get: vi.fn(() => Promise.resolve({ status: 'COMPLETED', processedFileCount: 1, fileCount: 2 })) },
      client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
    });

    await page.loadMetadata({ manual: true });

    const progress = graphProgress(dom);
    expect(progress.container.className).toBe('knowledge-graph-progress');
    expect(progress.main.className).toBe('knowledge-graph-progress-main');
    expect(progress.meta.className).toBe('knowledge-progress-meta');
    expect(progress.track.className).toBe('knowledge-progress-track');
    expect(progress.fill.style.width).toBe('50%');
    expect(dom.window.getComputedStyle(progress.container).display).toBe('grid');
    expect(dom.window.getComputedStyle(progress.container).padding).toBe('12px');
    expect(dom.window.getComputedStyle(progress.main).borderRadius).toBe('10px');
    expect(dom.window.getComputedStyle(progress.track).height).toBe('8px');
    expect(dom.window.getComputedStyle(progress.track).borderRadius).toBe('999px');
    expect(dom.window.getComputedStyle(progress.track).backgroundColor).toBe('rgba(27, 36, 31, 0.12)');
    expect(dom.window.getComputedStyle(progress.fill).backgroundColor).toBe('rgba(44, 123, 229, 0.82)');
  });

  it('UI-GRAPH-PARITY-06 keeps graph data loaded when metadata fails', async () => {
    const dom = graphDom();
    const metadataError = Object.assign(new Error('metadata down'), { code: 'GRAPH_METADATA_FAILED' });
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          return Promise.reject(metadataError);
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphView());
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { graphNodePageSize: 2, graphEdgePageSize: 2, graphPollIntervalMs: 60000 }
    });

    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('knowledgeGraphMetadataError')?.textContent).toContain('GRAPH_METADATA_FAILED');
    expect(page.state.data?.graphRevision).toBe('rev-a');
    expect(dom.window.document.getElementById('knowledgeGraphDetails')?.textContent).toContain('rev-a');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toBe('');
    page.dispose();
  });

  it('UI-KNOW-REG-04 keeps metadata and graph stale/dispose state separate', async () => {
    const dom = graphDom();
    const oldMetadata = new Promise((resolve) => setTimeout(() => resolve(metadataPayload(1, 'PARTIAL')), 10));
    let metadataCall = 0;
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/knowledge/analysis/graph/metadata')) {
          metadataCall += 1;
          return metadataCall === 1 ? oldMetadata : Promise.resolve(metadataPayload(9, 'COMPLETED'));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    let resolveSlowGraph: (value: unknown) => void = () => undefined;
    const slowGraph = new Promise((resolve) => {
      resolveSlowGraph = resolve;
    });
    const client = {
      loadSnapshot: vi.fn()
        .mockReturnValueOnce(slowGraph)
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
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, client });

    const oldMetadataRequest = page.loadMetadata({ manual: true });
    const newMetadataRequest = page.loadMetadata({ manual: true });
    await newMetadataRequest;
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('COMPLETED');
    await oldMetadataRequest;
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('COMPLETED');

    const oldGraphRequest = page.loadGraph({ manual: true });
    (dom.window.document.getElementById('knowledgeGraphFlowDomain') as HTMLSelectElement).value = 'CONFIG';
    page.updateUrlFromControls();
    const newGraphRequest = page.loadGraph({ manual: true });
    await newGraphRequest;
    resolveSlowGraph({
      ...manifest('rev-slow'),
      graphRevision: 'rev-slow',
      nodes: [node('slow')],
      edges: [],
      meta: { returnedNodeCount: 1, returnedEdgeCount: 0 }
    });
    await oldGraphRequest;
    expect(page.state.data?.graphRevision).toBe('rev-fast');

    const pendingDom = graphDom();
    let resolvePendingMetadata: (value: unknown) => void = () => undefined;
    const pendingMetadata = new Promise((resolve) => {
      resolvePendingMetadata = resolve;
    });
    const disposedPage = new KnowledgeGraphPage({
      document: pendingDom.window.document,
      window: pendingDom.window,
      http: { get: vi.fn(() => pendingMetadata) },
      client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() }
    });
    const request = disposedPage.loadMetadata({ manual: true });
    disposedPage.dispose();
    resolvePendingMetadata(metadataPayload(3, 'RUNNING'));
    await request;
    expect(pendingDom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('');
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
    page.selectNode('old-node');
    void page.openSelectedDetails();
    (dom.window.document.getElementById('knowledgeGraphFlowDomain') as HTMLSelectElement).value = 'CONFIG';
    page.updateUrlFromControls();
    page.resetFilterState();
    resolveDetail({ item: { id: 'old-node', evidence: [{ id: 'old-evidence' }] } });
    await page.loadGraph({ manual: true });

    expect(page.state.selectedNodeId).toBeNull();
    expect(page.state.data?.graphRevision).toBe('rev-config');
    expect(dom.window.document.getElementById('knowledgeGraphDetails')?.textContent).not.toContain('old-evidence');
  });

  it('UI-GRAPH-PARITY-01 restores main graph visual classes and constants', async () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });

    page.renderPage(graphData());
    await flushAsync(2);

    expect(dom.window.document.querySelector('.knowledge-graph-viewport')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-edge-layer')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-node-layer')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-node-label')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-edge.edge-calls')).toBeTruthy();
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(page.state.minimumZoom).toBeLessThanOrEqual(0.18);
  });

  it('UI-GRAPH-PARITY-02 wheel zoom changes the graph transform', async () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });
    page.renderPage(graphData());
    await flushAsync(2);
    page.state.transform.k = 1;
    const before = page.state.transform.k;
    const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;
    const event = new dom.window.WheelEvent('wheel', { deltaY: -120, clientX: 240, clientY: 160, bubbles: true, cancelable: true });

    svg.dispatchEvent(event);
    await flushAsync(2);

    expect(event.defaultPrevented).toBe(true);
    expect(page.metrics.wheelEventCount).toBeGreaterThan(0);
    expect(page.state.transform.k).toBeGreaterThan(before);
  });

  it('UI-GRAPH-PARITY-03 drag pan changes the graph transform without data reload', async () => {
    const dom = graphDom();
    const client = { loadSnapshot: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client });
    page.renderPage(graphData());
    await flushAsync(2);
    const before = { ...page.state.transform };
    const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;

    svg.dispatchEvent(new dom.window.PointerEvent('pointerdown', { clientX: 100, clientY: 100, bubbles: true }));
    svg.dispatchEvent(new dom.window.PointerEvent('pointermove', { clientX: 140, clientY: 128, bubbles: true }));
    svg.dispatchEvent(new dom.window.PointerEvent('pointerup', { clientX: 140, clientY: 128, bubbles: true }));
    await flushAsync(2);

    expect(page.state.transform.x).toBe(before.x + 40);
    expect(page.state.transform.y).toBe(before.y + 28);
    expect(page.metrics.panEventCount).toBeGreaterThan(0);
    expect(client.loadSnapshot).not.toHaveBeenCalled();
  });

  it('UI-GRAPH-PARITY-04 fit, panel, full, and focus controls keep graph usable', async () => {
    const dom = graphDom();
    const client = {
      loadSnapshot: vi.fn().mockResolvedValue(graphData('rev-full')),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const http = { get: vi.fn(() => Promise.resolve(metadataPayload())) };
    const page = new KnowledgeGraphPage({
      document: dom.window.document,
      window: dom.window,
      http,
      client,
      runtimeConfig: { graphPollIntervalMs: 60000 }
    });
    page.mount();
    await flushAsync();

    await page.selectNode('n1');
    expect(dom.window.document.getElementById('knowledgeGraphLayout')?.classList.contains('preview-collapsed')).toBe(false);

    dom.window.document.getElementById('toggleKnowledgeGraphPanel')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    expect(page.state.previewCollapsed).toBe(true);

    const previousFit = page.state.transform.k;
    dom.window.document.getElementById('fitKnowledgeGraph')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync(2);
    expect(page.state.transform.k).toBeGreaterThan(0);
    expect(Number.isFinite(page.state.transform.k)).toBe(true);

    dom.window.document.getElementById('focusKnowledgeGraph')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync(4);
    expect(dom.window.document.body.classList.contains('knowledge-graph-focus-mode')).toBe(true);
    expect(page.state.transform.k).toBeGreaterThan(0);

    dom.window.document.getElementById('showKnowledgeGraphFull')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    expect((dom.window.document.getElementById('knowledgeGraphMode') as HTMLSelectElement).value).toBe('full');
    expect(client.loadSnapshot).toHaveBeenCalled();
    expect(page.state.transform.k).toBeGreaterThan(0);
    expect(page.state.transform.k).not.toBe(Number.NaN);
    expect(previousFit).toBeGreaterThan(0);
    page.dispose();
  });

  it('UI-GRAPH-PARITY-01 keeps SVG renderer and exact radius constants', () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadSnapshot: vi.fn() } });
    expect(dom.window.document.getElementById('knowledgeGraphSvg')).toBeTruthy();
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(page.metrics.transformOnlyFrameCount).toBe(0);
  });
});
