import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { createKnowledgeGraphClient, graphDataFromView, graphLoadLimits, graphQuery, graphViewQuery } from '../src/operator/knowledge-graph-client.js';
import { KnowledgeGraphPage, knowledgeGraphNodeRadius } from '../src/operator/knowledge-graph-page.js';

function manifest(totalNodeCount = 300, totalEdgeCount = 0, revision = 'rev-a') {
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    graphRevision: revision,
    totalNodeCount,
    totalEdgeCount,
    status: { analysisStatus: 'COMPLETED' }
  };
}

function node(index: number, extra: Record<string, unknown> = {}) {
  return {
    id: `node-${String(index).padStart(5, '0')}`,
    nodeKind: index % 2 === 0 ? 'CALLABLE' : 'TYPE',
    label: `Name${index}`,
    name: `Name${index}`,
    qualifiedName: `com.example.Name${index}`,
    relativePath: index % 3 === 0 ? 'src/GraphFixture.java' : 'src/Other.java',
    flowDomain: 'CODE',
    ...extra
  };
}

function edge(index: number) {
  return {
    id: `edge-${String(index).padStart(5, '0')}`,
    fromNodeId: `node-${String(index).padStart(5, '0')}`,
    toNodeId: `node-${String(index + 1).padStart(5, '0')}`,
    edgeType: 'CALLS',
    flowDomain: 'CODE'
  };
}

function metadataPayload() {
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    status: 'COMPLETED',
    processedFileCount: 10,
    fileCount: 10,
    graphAvailable: true,
    graphRevision: 'rev-a'
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
      <select id="knowledgeGraphFlowDomain"><option value="">All</option><option value="CODE">Code</option><option value="CONFIG">Config</option><option value="WORKFLOW">Workflow</option></select>
      <select id="knowledgeGraphDirection"><option value="OUTBOUND">Outbound</option><option value="INBOUND">Inbound</option><option value="BOTH">Both</option></select>
      <select id="knowledgeGraphDepth"><option value="1">1</option><option value="2">2</option><option value="4">4</option></select>
      <select id="knowledgeGraphExternal"><option value="collapsed">Collapsed</option><option value="show">Show</option><option value="hide">Hide</option></select>
      <select id="knowledgeGraphUnresolved"><option value="summarize">Summarize</option><option value="show">Show</option><option value="hide">Hide</option></select>
      <select id="knowledgeGraphDensity"><option value="compact">Compact</option><option value="normal">Normal</option><option value="spacious">Spacious</option></select>
      <select id="knowledgeGraphLabelsMode"><option value="auto">Auto</option><option value="all">All</option><option value="none">None</option></select>
      <select id="knowledgeGraphMaxNodes"><option value="20">20</option><option value="40">40</option><option value="80">80</option><option value="120">120</option><option value="200">200</option><option value="0">All</option></select>
      <select id="knowledgeGraphIsolated"><option value="hide">Hide</option><option value="show">Show</option></select>
      <input id="knowledgeGraphAutoRefresh" type="checkbox">
      <input id="knowledgeGraphSearch">
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

async function flushAsync(turns = 6) {
  for (let index = 0; index < turns; index += 1) {
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function selectValue(dom: JSDOM, id: string, value: string) {
  const element = dom.window.document.getElementById(id) as HTMLSelectElement;
  element.value = value;
  element.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
}

function graphPageData(count: number, total = 300, revision = `rev-${count || 'all'}`) {
  return {
    ...manifest(total, total * 2, revision),
    nodes: Array.from({ length: count }, (_, index) => node(index)),
    edges: [],
    meta: {
      truncated: count < total,
      returnedNodeCount: count,
      returnedEdgeCount: 0,
      totalNodeCount: total,
      totalEdgeCount: total * 2,
      skippedByLimitCount: Math.max(0, total - count),
      truncationReason: count < total ? 'client max limit' : null
    }
  };
}

function graphViewPayload(count: number, total = 300, revision = `rev-${count || 'all'}`, edgeCount = Math.max(0, count - 1)) {
  const nodes = Array.from({ length: count }, (_, index) => node(index));
  const edges = Array.from({ length: edgeCount }, (_, index) => edge(index)).filter((item) => {
    const from = Number(String(item.fromNodeId).split('-').at(-1));
    const to = Number(String(item.toNodeId).split('-').at(-1));
    return from < count && to < count;
  });
  return {
    sourceId: 'forge-ai',
    sourceName: 'Forge AI',
    graphId: 'graph-a',
    graphRevision: revision,
    queryFingerprint: `fingerprint-${revision}`,
    selectionPolicy: 'RELATIONSHIP_AWARE',
    maxNodes: count >= total ? 0 : count,
    filters: {},
    nodes,
    edges,
    totalMatchingNodeCount: total,
    totalMatchingEdgeCount: Math.max(total - 1, edges.length),
    visibleNodeCount: nodes.length,
    visibleEdgeCount: edges.length,
    hiddenNodeCount: Math.max(0, total - nodes.length),
    hiddenEdgeCount: Math.max(0, total - 1 - edges.length),
    hiddenBoundaryEdgeCount: Math.max(0, total - count),
    internalEdgeCount: edges.length,
    hasMore: count < total,
    status: { analysisStatus: 'COMPLETED' }
  };
}

function graphDetailRequests(requests: string[]) {
  return requests.filter((path) => /\/knowledge\/analysis\/graph\/(node|edge)\//.test(path));
}

function nodeDetailFixture(id = 'node-00000') {
  return {
    graphRevision: 'rev-a',
    graphId: 'graph-a',
    item: {
      id,
      label: 'Detail Node',
      nodeKind: 'CALLABLE',
      sourceId: 'forge-ai',
      relativePath: 'src/Detail.java',
      lineStart: 12,
      lineEnd: 18,
      claimSummary: 'Does important work',
      responsibilitySummary: 'Coordinates graph detail loading',
      claims: [{ id: 'claim-1', claimKind: 'RESPONSIBILITY', summary: 'Coordinates graph detail loading', status: 'TRUSTED', confidence: 0.91 }],
      evidence: [{ id: 'ev-node', text: 'Uses parsed AST evidence', relativePath: 'src/Detail.java', lineStart: 12 }],
      relations: {
        outgoing: {
          totalCount: 2,
          items: [
            {
              edgeId: 'edge-out-1',
              edgeKind: 'CALLS',
              sourceNodeId: id,
              sourceName: 'Detail Node',
              sourceKind: 'CALLABLE',
              targetNodeId: 'node-00001',
              targetName: 'buildRequest',
              targetKind: 'CALLABLE',
              sourcePath: 'src/Detail.java',
              lineStart: 14,
              lineEnd: 14,
              confidence: 0.9,
              evidenceCount: 1
            },
            {
              edgeId: 'edge-out-2',
              edgeKind: 'REFERENCES',
              sourceNodeId: id,
              sourceName: 'Detail Node',
              sourceKind: 'CALLABLE',
              targetNodeId: 'node-00002',
              targetName: 'ConversationStatus',
              targetKind: 'TYPE',
              sourcePath: 'src/Detail.java',
              lineStart: 15,
              lineEnd: 15,
              confidence: 0.88,
              evidenceCount: 0
            }
          ]
        },
        incoming: {
          totalCount: 1,
          items: [
            {
              edgeId: 'edge-in-1',
              edgeKind: 'CONTAINS',
              sourceNodeId: 'node-00003',
              sourceName: 'DetailContainer',
              sourceKind: 'TYPE',
              targetNodeId: id,
              targetName: 'Detail Node',
              targetKind: 'CALLABLE',
              sourcePath: 'src/Detail.java',
              lineStart: 12,
              lineEnd: 18,
              confidence: 1,
              evidenceCount: 1
            }
          ]
        }
      }
    }
  };
}

describe('Knowledge graph filters and max limit', () => {
  it('UI-GRAPH-FILTER-01 default Max is respected', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(80, 300, 'rev-a', 79));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphNodePageSize: 500, graphEdgePageSize: 1000, graphPollIntervalMs: 60000 } });

    page.mount();
    await flushAsync();

    const viewRequests = requests.filter((path) => path.includes('/knowledge/analysis/graph/view'));
    expect((dom.window.document.getElementById('knowledgeGraphMaxNodes') as HTMLSelectElement).value).toBe('80');
    expect(viewRequests).toHaveLength(1);
    expect(new URL(viewRequests[0] || '', 'http://127.0.0.1').searchParams.get('maxNodes')).toBe('80');
    expect(page.state.data.nodes).toHaveLength(80);
    expect(page.state.nodes.length).toBe(80);
    expect(page.state.edges.length).toBeGreaterThan(0);
    expect(page.state.data.meta.truncated).toBe(true);
    expect(requests.some((path) => path.includes('includeExternal=collapsed'))).toBe(false);
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).not.toContain('400');
    page.dispose();
  });

  it('UI-GRAPH-FILTER-02 Max changes reset and reload graph', async () => {
    const dom = graphDom();
    const client = {
      loadGraphData: vi.fn((query: URLSearchParams) => Promise.resolve({
        ...manifest(Number(query.get('maxNodes') || 300), 0, `rev-${query.get('maxNodes') || 'all'}`),
        nodes: Array.from({ length: Number(query.get('maxNodes') || 5) || 5 }, (_, index) => node(index)),
        edges: [],
        meta: { returnedNodeCount: Number(query.get('maxNodes') || 5), returnedEdgeCount: 0, totalNodeCount: 300, totalEdgeCount: 0 }
      })),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const http = { get: vi.fn(() => Promise.resolve(metadataPayload())) };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    selectValue(dom, 'knowledgeGraphMaxNodes', '20');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphMaxNodes', '80');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphMaxNodes', '200');
    await flushAsync();

    const maxValues = client.loadGraphData.mock.calls.map(([query]) => query.get('maxNodes'));
    expect(maxValues).toEqual(['80', '20', '80', '200']);
    expect(page.state.selectedNodeId).toBeNull();
    expect(page.state.data.nodes).toHaveLength(200);
    expect(http.get).toHaveBeenCalledTimes(1);
    page.dispose();
  });

  it('UI-GRAPH-FILTER-03 full filter matrix is classified against final APIs', () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&flowDomain=WORKFLOW&includeExternal=hide&unresolved=hide&isolated=show&maxNodes=200&search=GraphFixture&direction=INBOUND&depth=4&density=spacious&labels=all');
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadGraphData: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });
    page.initializeControls();

    const { query } = page.queryParams();
    expect(Object.fromEntries(query.entries())).toMatchObject({
      sourceId: 'forge-ai',
      flowDomain: 'WORKFLOW',
      includeExternal: 'hide',
      includeUnresolved: 'false',
      includeIsolated: 'true',
      maxNodes: '200',
      search: 'GraphFixture'
    });
    expect((dom.window.document.getElementById('knowledgeGraphDirection') as HTMLSelectElement).disabled).toBe(true);
    expect((dom.window.document.getElementById('knowledgeGraphDepth') as HTMLSelectElement).disabled).toBe(true);
    expect(graphLoadLimits(query)).toEqual({ nodeLimit: 200, edgeLimit: 800 });
  });

  it('UI-GRAPH-FILTER-04 no obsolete params are sent to final graph requests', () => {
    const apiQuery = graphQuery(new URLSearchParams({
      sourceId: 'forge-ai',
      flowDomain: 'CODE',
      includeExternal: 'collapsed',
      mode: 'Compact slice',
      direction: 'Outbound',
      depth: '2',
      maxNodes: '80',
      search: 'Name1'
    }));

    expect(apiQuery.get('includeExternal')).toBe('show');
    expect(apiQuery.get('search')).toBe('Name1');
    ['mode', 'direction', 'depth', 'maxNodes'].forEach((param) => expect(apiQuery.has(param)).toBe(false));
    expect(graphViewQuery(new URLSearchParams({
      sourceId: 'forge-ai',
      includeExternal: 'collapsed',
      direction: 'Outbound',
      depth: '2',
      maxNodes: '80'
    })).toString()).toBe('sourceId=forge-ai&includeExternal=show&maxNodes=80');
  });

  it('UI-GRAPH-FILTER-05 search filter works by node, file, and kind', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          const url = new URL(path, 'http://127.0.0.1');
          const search = url.searchParams.get('search') || '';
          const all = [node(1), node(2, { relativePath: 'src/SpecialWorkflow.java' }), node(3, { nodeKind: 'CONFIG' })];
          const items = all.filter((item) => [item.id, item.label, item.nodeKind, item.relativePath].join(' ').toLowerCase().includes(search.toLowerCase()));
          return Promise.resolve({ ...graphViewPayload(items.length, items.length, 'rev-a', 0), nodes: items, visibleNodeCount: items.length, totalMatchingNodeCount: items.length, hiddenNodeCount: 0, hasMore: false });
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    const input = dom.window.document.getElementById('knowledgeGraphSearch') as HTMLInputElement;
    input.value = 'SpecialWorkflow';
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    await flushAsync();

    const searchedViewRequest = requests.filter((path) => path.includes('/view')).at(-1) || '';
    expect(new URL(searchedViewRequest, 'http://127.0.0.1').searchParams.get('search')).toBe('SpecialWorkflow');
    expect(page.state.data.nodes).toHaveLength(1);
    expect(page.state.data.nodes[0].relativePath).toBe('src/SpecialWorkflow.java');
    page.dispose();
  });

  it('UI-GRAPH-FILTER-06 client-only display options do not hit API', async () => {
    const dom = graphDom();
    const client = { loadGraphData: vi.fn().mockResolvedValue({ ...manifest(2, 1), nodes: [node(0), node(1)], edges: [{ ...edge(0), from: 'node-00000', to: 'node-00001' }], meta: { returnedNodeCount: 2, returnedEdgeCount: 1 } }), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    const callsBefore = client.loadGraphData.mock.calls.length;

    selectValue(dom, 'knowledgeGraphDensity', 'spacious');
    selectValue(dom, 'knowledgeGraphLabelsMode', 'all');
    await flushAsync();

    expect(client.loadGraphData).toHaveBeenCalledTimes(callsBefore);
    expect(page.state.density).toBe('spacious');
    expect(page.state.labelsMode).toBe('all');
    expect(page.state.nodes.length).toBe(2);
    page.dispose();
  });

  it('UI-GRAPH-FILTER-07 stale response isolation keeps the newest filter state', async () => {
    const dom = graphDom();
    let resolveA: (value: unknown) => void = () => undefined;
    const requestA = new Promise((resolve) => {
      resolveA = resolve;
    });
    const client = {
      loadGraphData: vi.fn()
        .mockReturnValueOnce(requestA)
        .mockResolvedValueOnce({ ...manifest(1, 0, 'rev-b'), nodes: [node(200)], edges: [], meta: { returnedNodeCount: 1, returnedEdgeCount: 0 } }),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client });

    const oldLoad = page.loadGraph({ manual: true });
    (dom.window.document.getElementById('knowledgeGraphFlowDomain') as HTMLSelectElement).value = 'CONFIG';
    page.updateUrlFromControls();
    const newLoad = page.loadGraph({ manual: true });
    await newLoad;
    resolveA({ ...manifest(1, 0, 'rev-a'), nodes: [node(1)], edges: [], meta: { returnedNodeCount: 1, returnedEdgeCount: 0 } });
    await oldLoad;

    expect(page.state.data.graphRevision).toBe('rev-b');
    expect(page.state.data.nodes[0].id).toBe('node-00200');
    page.renderPage(page.state.data, { preserveLayout: true });
    const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;
    svg.dispatchEvent(new dom.window.PointerEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true }));
    svg.dispatchEvent(new dom.window.PointerEvent('pointermove', { clientX: 20, clientY: 20, bubbles: true }));
    expect(page.metrics.panEventCount).toBeGreaterThan(0);
  });

  it('UI-GRAPH-FILTER-08 visual parity with main is retained', () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadGraphData: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });
    page.renderPage({ ...manifest(2, 1), nodes: [node(0), node(1)], edges: [{ ...edge(0), from: 'node-00000', to: 'node-00001' }], meta: { returnedNodeCount: 2, returnedEdgeCount: 1 } });

    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(dom.window.document.querySelector('.knowledge-graph-edge.edge-calls')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-node-label')).toBeTruthy();
    expect(page.state.minimumZoom).toBeLessThanOrEqual(0.18);
  });

  it('UI-GRAPH-FILTER-09 no old graph routes are called', async () => {
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(1, 1, 'rev-a', 0));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const client = createKnowledgeGraphClient({ http, config: { graphNodePageSize: 80, graphEdgePageSize: 80 } });
    await client.loadGraphData(new URLSearchParams({ sourceId: 'forge-ai', flowDomain: 'CODE', maxNodes: '80' }));

    expect(requests.some((path) => /\/analysis\/symbols|\/analysis\/relations|\/analysis\/graph\/slice|\/analysis\/graph($|\?)/.test(path))).toBe(false);
  });

  it('UI-GRAPH-FILTER-10 performance guard for a large graph stays bounded by Max', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(80, 300, 'rev-a', 79));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphNodePageSize: 500, graphEdgePageSize: 1000, graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    expect(page.state.data.nodes).toHaveLength(80);
    expect(page.state.nodes.length).toBe(80);
    expect(page.state.edges.length).toBe(79);
    expect(requests.filter((path) => path.includes('/view'))).toHaveLength(1);
    expect(new URL(requests.find((path) => path.includes('/view')) || '', 'http://127.0.0.1').searchParams.get('maxNodes')).toBe('80');
    const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;
    svg.dispatchEvent(new dom.window.WheelEvent('wheel', { deltaY: -100, clientX: 100, clientY: 100, bubbles: true, cancelable: true }));
    await flushAsync(2);
    expect(page.metrics.wheelEventCount).toBeGreaterThan(0);
    page.dispose();
  });

  it('UI-GRAPH-FILTER-RT-01 Max 20/40/80/120/200 runtime counts are bounded and monotonic', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          const maxNodes = Number(new URL(path, 'http://127.0.0.1').searchParams.get('maxNodes') || '0');
          return Promise.resolve(graphViewPayload(Math.min(maxNodes || 300, 300), 300, `rev-${maxNodes || 'all'}`, Math.max(0, Math.min(maxNodes || 300, 300) - 1)));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphNodePageSize: 500, graphEdgePageSize: 1000, graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    const counts: number[] = [];
    for (const value of ['20', '40', '80', '120', '200']) {
      selectValue(dom, 'knowledgeGraphMaxNodes', value);
      await flushAsync();
      counts.push(page.state.nodes.length);
      const viewRequest = requests.filter((path) => path.includes('/knowledge/analysis/graph/view')).at(-1) || '';
      const params = new URL(viewRequest, 'http://127.0.0.1').searchParams;
      expect(params.get('maxNodes')).toBe(value);
      expect(params.has('cursor')).toBe(false);
    }

    expect(counts).toEqual([20, 40, 80, 120, 200]);
    expect(counts).toEqual([...counts].sort((left, right) => left - right));
    expect(page.state.data.nodes).toHaveLength(200);
    page.dispose();
  });

  it('UI-GRAPH-FILTER-RT-02 Max state ignores late graph view responses from older queries', async () => {
    const dom = graphDom();
    let resolveOldView: (value: unknown) => void = () => undefined;
    const oldView = new Promise((resolve) => {
      resolveOldView = resolve;
    });
    let viewCallCount = 0;
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          viewCallCount += 1;
          const max = new URL(path, 'http://127.0.0.1').searchParams.get('maxNodes') || '80';
          if (max === '80' && viewCallCount === 1) {
            return oldView;
          }
          return Promise.resolve(graphViewPayload(Number(max), 300, `rev-${max}`, Number(max) - 1));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphNodePageSize: 500, graphEdgePageSize: 1000, graphPollIntervalMs: 60000 } });
    page.mount();
    selectValue(dom, 'knowledgeGraphMaxNodes', '120');
    await flushAsync();
    resolveOldView(graphViewPayload(80, 300, 'rev-80', 79));
    await flushAsync();

    expect(page.state.data.meta.maxNodeLimit).toBe(120);
    expect(page.state.nodes).toHaveLength(120);
    expect(page.state.data.meta.returnedNodeCount).toBe(120);
    expect(dom.window.document.getElementById('knowledgeGraphTruncated')?.textContent).toContain('Showing 120 of 300');
    page.dispose();
  });

  it('UI-GRAPH-FILTER-RT-03 every visible filter is API, client-only, or disabled obsolete', async () => {
    const dom = graphDom();
    const client = {
      loadGraphData: vi.fn((query: URLSearchParams) => {
        const max = Number(query.get('maxNodes') || '80') || 300;
        return Promise.resolve(graphPageData(Math.min(max, 300), 300, `rev-${query.toString()}`));
      }),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    const callsBeforeDisplay = client.loadGraphData.mock.calls.length;

    selectValue(dom, 'knowledgeGraphDensity', 'spacious');
    selectValue(dom, 'knowledgeGraphLabelsMode', 'all');
    selectValue(dom, 'knowledgeGraphMode', 'full');
    await flushAsync();
    expect(client.loadGraphData).toHaveBeenCalledTimes(callsBeforeDisplay);
    expect((dom.window.document.getElementById('knowledgeGraphDirection') as HTMLSelectElement).disabled).toBe(true);
    expect((dom.window.document.getElementById('knowledgeGraphDepth') as HTMLSelectElement).disabled).toBe(true);

    selectValue(dom, 'knowledgeGraphFlowDomain', 'WORKFLOW');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphExternal', 'hide');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphUnresolved', 'hide');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphIsolated', 'show');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphMaxNodes', '40');
    await flushAsync();
    const input = dom.window.document.getElementById('knowledgeGraphSearch') as HTMLInputElement;
    input.value = 'Name4';
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    await flushAsync();

    const lastQuery = client.loadGraphData.mock.calls.at(-1)?.[0] as URLSearchParams;
    expect(Object.fromEntries(lastQuery.entries())).toMatchObject({
      flowDomain: 'WORKFLOW',
      includeExternal: 'hide',
      includeUnresolved: 'false',
      includeIsolated: 'true',
      maxNodes: '40',
      search: 'Name4'
    });
    page.dispose();
  });

  it('UI-GRAPH-FILTER-RT-04 production graph requests never send invalid old params', async () => {
    const dom = graphDom('http://127.0.0.1/operator/knowledge-graph.html?sourceId=forge-ai&mode=Compact%20slice&direction=Outbound&depth=2&includeExternal=collapsed&maxNodes=80');
    const requests: string[] = [];
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(80, 80, 'rev-a', 79));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    const graphRequests = requests.filter((path) => /\/knowledge\/analysis\/graph\/view/.test(path));
    expect(graphRequests.some((path) => path.includes('includeExternal=collapsed'))).toBe(false);
    expect(graphRequests.some((path) => path.includes('mode=Compact'))).toBe(false);
    expect(graphRequests.some((path) => path.includes('direction=Outbound'))).toBe(false);
    expect(graphRequests.some((path) => path.includes('depth=2'))).toBe(false);
    page.dispose();
  });

  it('UI-GRAPH-FILTER-RT-05 visual parity survives Max/filter runtime fixes', () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadGraphData: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });
    page.renderPage({
      ...manifest(40, 39),
      nodes: Array.from({ length: 40 }, (_, index) => node(index)),
      edges: Array.from({ length: 39 }, (_, index) => ({ ...edge(index), from: `node-${String(index).padStart(5, '0')}`, to: `node-${String(index + 1).padStart(5, '0')}` })),
      meta: { returnedNodeCount: 40, returnedEdgeCount: 39, totalNodeCount: 40, totalEdgeCount: 39 }
    });

    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(dom.window.document.querySelectorAll('.knowledge-graph-node')).toHaveLength(40);
    expect(dom.window.document.querySelector('.knowledge-graph-edge.edge-calls')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-node-label')).toBeTruthy();
    expect(page.state.minimumZoom).toBeLessThanOrEqual(0.18);
  });

  it('UI-GRAPH-VIEW-01 Max 20/40/80/120/200 returns a coherent subgraph', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          const max = Number(new URL(path, 'http://127.0.0.1').searchParams.get('maxNodes') || '80');
          return Promise.resolve(graphViewPayload(max, 320, `rev-${max}`, max - 1));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    for (const value of ['20', '40', '80', '120', '200']) {
      selectValue(dom, 'knowledgeGraphMaxNodes', value);
      await flushAsync();
      const nodeIds = new Set(page.state.nodes.map((item: { id: string }) => item.id));
      expect(page.state.nodes).toHaveLength(Number(value));
      expect(page.state.edges.length).toBeGreaterThan(0);
      expect(page.state.edges.every((item: { from: string; to: string }) => nodeIds.has(item.from) && nodeIds.has(item.to))).toBe(true);
      expect(dom.window.document.getElementById('knowledgeGraphTruncated')?.textContent).toContain(`Showing ${value} of 320`);
    }
    page.dispose();
  });

  it('UI-GRAPH-VIEW-02 small Max is connected and deterministic', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          const max = Number(new URL(path, 'http://127.0.0.1').searchParams.get('maxNodes') || '80');
          return Promise.resolve(graphViewPayload(max, 320, `rev-${max}`, max - 1));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    selectValue(dom, 'knowledgeGraphMaxNodes', '20');
    await flushAsync();
    const firstIds = page.state.nodes.map((item: { id: string }) => item.id);
    const connectedIds = new Set(page.state.edges.flatMap((item: { from: string; to: string }) => [item.from, item.to]));
    expect(firstIds.every((id: string) => connectedIds.has(id))).toBe(true);

    await page.loadGraph({ manual: true });
    expect(page.state.nodes.map((item: { id: string }) => item.id)).toEqual(firstIds);

    selectValue(dom, 'knowledgeGraphMaxNodes', '40');
    await flushAsync();
    expect(page.state.edges.length).toBeGreaterThan(0);
    expect(page.state.nodes.some((item: { id: string }) => page.state.edges.some((edgeItem: { from: string; to: string }) => edgeItem.from === item.id || edgeItem.to === item.id))).toBe(true);
    page.dispose();
  });

  it('UI-GRAPH-VIEW-03 filters apply to graph view and stale responses are ignored', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    let resolveOldView: (value: unknown) => void = () => undefined;
    const oldView = new Promise((resolve) => {
      resolveOldView = resolve;
    });
    let viewCallCount = 0;
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          viewCallCount += 1;
          if (viewCallCount === 1) {
            return oldView;
          }
          const url = new URL(path, 'http://127.0.0.1');
          return Promise.resolve({
            ...graphViewPayload(2, 2, `rev-${viewCallCount}`, 1),
            nodes: [
              node(10, { flowDomain: url.searchParams.get('flowDomain') }),
              node(11, { label: 'Name10Peer', name: 'Name10Peer', flowDomain: url.searchParams.get('flowDomain') })
            ],
            edges: [{ ...edge(10), fromNodeId: 'node-00010', toNodeId: 'node-00011' }],
            visibleNodeCount: 2,
            totalMatchingNodeCount: 2,
            hiddenNodeCount: 0,
            hiddenEdgeCount: 0,
            hasMore: false
          });
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    selectValue(dom, 'knowledgeGraphFlowDomain', 'WORKFLOW');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphExternal', 'hide');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphUnresolved', 'hide');
    await flushAsync();
    selectValue(dom, 'knowledgeGraphIsolated', 'show');
    await flushAsync();
    const input = dom.window.document.getElementById('knowledgeGraphSearch') as HTMLInputElement;
    input.value = 'Name10';
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    await flushAsync();
    resolveOldView(graphViewPayload(80, 320, 'rev-old', 79));
    await flushAsync();

    const latest = requests.filter((path) => path.includes('/knowledge/analysis/graph/view')).at(-1) || '';
    const params = new URL(latest, 'http://127.0.0.1').searchParams;
    expect(Object.fromEntries(params.entries())).toMatchObject({
      flowDomain: 'WORKFLOW',
      includeExternal: 'hide',
      includeUnresolved: 'false',
      includeIsolated: 'true',
      search: 'Name10'
    });
    expect(page.state.data.graphRevision).not.toBe('rev-old');
    expect(page.state.nodes).toHaveLength(2);
    page.dispose();
  });

  it('UI-GRAPH-VIEW-04 client-only controls stay local and obsolete controls remain disabled', async () => {
    const dom = graphDom();
    const client = {
      loadGraphData: vi.fn().mockResolvedValue(graphPageData(20, 320, 'rev-a')),
      loadNodeDetail: vi.fn(),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    const callsBefore = client.loadGraphData.mock.calls.length;

    selectValue(dom, 'knowledgeGraphDensity', 'normal');
    selectValue(dom, 'knowledgeGraphLabelsMode', 'none');
    selectValue(dom, 'knowledgeGraphMode', 'full');
    await flushAsync();

    expect(client.loadGraphData).toHaveBeenCalledTimes(callsBefore);
    expect(page.state.density).toBe('normal');
    expect(page.state.labelsMode).toBe('none');
    expect((dom.window.document.getElementById('knowledgeGraphDirection') as HTMLSelectElement).disabled).toBe(true);
    expect((dom.window.document.getElementById('knowledgeGraphDepth') as HTMLSelectElement).disabled).toBe(true);
    page.dispose();
  });

  it('UI-GRAPH-VIEW-05 visual parity remains main-compatible with view data', () => {
    const dom = graphDom();
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: {}, client: { loadGraphData: vi.fn(), loadNodeDetail: vi.fn(), loadEdgeDetail: vi.fn() } });
    const data = graphDataFromView(graphViewPayload(40, 320, 'rev-view', 39));
    page.renderPage(data);

    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'CALLABLE' }, {})).toBe(19);
    expect(knowledgeGraphNodeRadius({ id: 'n', nodeKind: 'TYPE' }, {})).toBe(22);
    expect(dom.window.document.querySelector('.knowledge-graph-edge.edge-calls')).toBeTruthy();
    expect(dom.window.document.querySelector('.knowledge-graph-node-label')).toBeTruthy();
    expect(page.state.minimumZoom).toBeLessThanOrEqual(0.18);
  });

  it('UI-GRAPH-VIEW-06 metadata remains independent when graph view fails', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.reject(Object.assign(new Error('GRAPH_VIEW_FAILED'), { code: 'GRAPH_VIEW_FAILED' }));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('Forge AI');
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('COMPLETED');
    expect(dom.window.document.getElementById('knowledgeGraphProgress')?.textContent).toContain('10 / 10 files');
    expect(dom.window.document.getElementById('knowledgeGraphError')?.textContent).toContain('GRAPH_VIEW_FAILED');
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-01 no lower Facts details section', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return Promise.resolve(nodeDetailFixture());
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();

    expect(dom.window.document.querySelector('.knowledge-graph-details-panel')).toBeNull();
    expect(dom.window.document.querySelector('[data-graph-tab]')).toBeNull();
    expect(dom.window.document.getElementById('knowledgeGraphDetails')).toBeNull();
    expect(dom.window.document.getElementById('knowledgeGraphPreview')?.textContent).toContain('Coordinates graph detail loading');
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-02 no Open Details button', async () => {
    const dom = graphDom();
    const requests: string[] = [];
    let resolveDetail: (value: unknown) => void = () => undefined;
    const pendingDetail = new Promise((resolve) => {
      resolveDetail = resolve;
    });
    const http = {
      get: vi.fn((path: string) => {
        requests.push(path);
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return pendingDetail;
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    const preview = dom.window.document.getElementById('knowledgeGraphPreview');
    expect(preview?.textContent).toContain('Loading details');
    expect(preview?.textContent).toContain('Center');
    expect(preview?.textContent).not.toContain('Open details');
    expect(preview?.querySelector('[data-open-graph-details]')).toBeNull();
    await flushAsync(1);

    const detailRequests = graphDetailRequests(requests);
    expect(detailRequests).toHaveLength(1);
    const detailRequest = detailRequests[0] || '';
    expect(detailRequest).toContain('/knowledge/analysis/graph/node/node-00000');
    const detailUrl = new URL(detailRequest, 'http://127.0.0.1');
    expect(detailUrl.searchParams.get('sourceId')).toBe('forge-ai');
    expect(detailUrl.searchParams.get('graphRevision')).toBe('rev-a');
    expect(detailUrl.searchParams.get('includeEvidence')).toBe('true');
    expect(requests.some((path) => /\/analysis\/graph\/slice|\/analysis\/symbols|\/analysis\/relations|\/analysis\/graph($|\?)/.test(path))).toBe(false);
    resolveDetail(nodeDetailFixture());
    await flushAsync();
    expect(preview?.textContent).toContain('Purpose');
    expect(preview?.textContent).not.toContain('Open details');
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-03 no technical IDs in normal UI', async () => {
    const dom = graphDom();
    const technicalNodeId = 'analysis-graph-node:1234567890abcdef';
    const technicalDetail = {
      graphRevision: 'graphRevision-secret-hash',
      graphId: 'graph-secret-hash',
      queryFingerprint: 'fingerprint-secret-hash',
      item: {
        id: technicalNodeId,
        label: 'SiteUpdatedEventMapper',
        nodeKind: 'TYPE',
        flowDomain: 'CODE',
        factOrigin: 'STATIC',
        sourceId: 'source-internal-id',
        relativePath: '/home/user/workspace/pipe/event/src/main/java/com/example/SiteUpdatedEventMapper.java',
        lineStart: 9,
        lineEnd: 28,
        responsibilitySummary: 'Maps SiteUpdatedPayload to SiteUpdatedEvent.',
        summaryClaimId: 'claim-hash-secret',
        claims: [{ id: 'claim-hash-secret', claimKind: 'RESPONSIBILITY', summary: 'Maps SiteUpdatedPayload to SiteUpdatedEvent.', status: 'TRUSTED' }],
        evidence: [{ id: 'evidence-hash-secret', excerptHash: 'excerpt-hash-secret', text: 'Mapper uses payload fields.', relativePath: 'src/main/java/SiteUpdatedEventMapper.java', lineStart: 12 }],
        relations: {
          outgoing: { totalCount: 1, items: [{ edgeId: 'edge-hash-secret', edgeKind: 'REFERENCES', targetNodeId: 'target-node-secret', targetName: 'SiteUpdatedPayload', targetKind: 'TYPE' }] },
          incoming: { totalCount: 0, items: [] }
        }
      }
    };
    const client = {
      loadGraphData: vi.fn().mockResolvedValue({
        ...manifest(1, 0, 'graphRevision-secret-hash'),
        graphId: 'graph-secret-hash',
        queryFingerprint: 'fingerprint-secret-hash',
        nodes: [node(0, { id: technicalNodeId, label: 'SiteUpdatedEventMapper', name: 'SiteUpdatedEventMapper', nodeKind: 'TYPE', flowDomain: 'CODE' })],
        edges: [],
        meta: { returnedNodeCount: 1, returnedEdgeCount: 0, totalNodeCount: 1, totalEdgeCount: 0 }
      }),
      loadNodeDetail: vi.fn(() => Promise.resolve(technicalDetail)),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode(technicalNodeId);
    await flushAsync();

    const visibleText = [
      dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '',
      dom.window.document.getElementById('knowledgeGraphProgress')?.textContent || '',
      dom.window.document.getElementById('knowledgeGraphSubtitle')?.textContent || ''
    ].join(' ');
    expect(visibleText).toContain('SiteUpdatedEventMapper');
    expect(visibleText).toContain('Maps SiteUpdatedPayload to SiteUpdatedEvent.');
    expect(visibleText).toContain('SiteUpdatedEventMapper.java');
    ['analysis-graph-node:', 'graphRevision-secret-hash', 'graph-secret-hash', 'fingerprint-secret-hash', 'claim-hash-secret', 'evidence-hash-secret', 'edge-hash-secret', 'source-internal-id', 'excerpt-hash-secret'].forEach((value) => {
      expect(visibleText).not.toContain(value);
    });
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-04 purpose is visible and primary', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return Promise.resolve(nodeDetailFixture());
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();

    const previewText = dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '';
    expect(previewText).toContain('Purpose');
    expect(previewText).toContain('Coordinates graph detail loading');
    expect(previewText.indexOf('Purpose')).toBeLessThan(previewText.indexOf('Claims'));
    expect(previewText.indexOf('Purpose')).toBeLessThan(previewText.indexOf('Evidence'));
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-05 relationships are readable and compact', async () => {
    const dom = graphDom();
    const outgoing = Array.from({ length: 7 }, (_, index) => ({
      edgeId: `edge-out-${index}`,
      edgeKind: index % 2 === 0 ? 'DECLARES' : 'REFERENCES',
      sourceNodeId: 'node-00000',
      sourceName: 'Detail Node',
      sourceKind: 'CALLABLE',
      targetNodeId: `target-${index}`,
      targetName: `Neighbor${index}`,
      targetKind: index % 2 === 0 ? 'CALLABLE' : 'TYPE',
      sourcePath: 'src/Detail.java',
      lineStart: 20 + index
    }));
    const incoming = Array.from({ length: 6 }, (_, index) => ({
      edgeId: `edge-in-${index}`,
      edgeKind: 'CONTAINS',
      sourceNodeId: `source-${index}`,
      sourceName: `Container${index}`,
      sourceKind: 'FILE',
      targetNodeId: 'node-00000',
      targetName: 'Detail Node',
      targetKind: 'CALLABLE',
      sourcePath: 'src/Detail.java',
      lineStart: 10 + index
    }));
    const detail = {
      ...nodeDetailFixture(),
      item: {
        ...nodeDetailFixture().item,
        relations: {
          outgoing: { totalCount: outgoing.length, items: outgoing },
          incoming: { totalCount: incoming.length, items: incoming }
        }
      }
    };
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return Promise.resolve(detail);
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();

    const previewText = dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '';
    expect(previewText).toContain('Relationships');
    expect(previewText).toContain('Outgoing 7');
    expect(previewText).toContain('DECLARES -> Neighbor0');
    expect(previewText).toContain('REFERENCES -> Neighbor1');
    expect(previewText).toContain('+2 more');
    expect(previewText).toContain('Incoming 6');
    expect(previewText).toContain('CONTAINS <- Container0');
    expect(previewText).toContain('+1 more');
    expect(previewText).not.toContain('edge-out-');
    expect(previewText).not.toContain('edge-in-');
    expect(dom.window.document.querySelectorAll('#knowledgeGraphPreview .knowledge-graph-fact-list article')).toHaveLength(0);
    expect(dom.window.document.querySelectorAll('#knowledgeGraphPreview .knowledge-graph-relation-row')).toHaveLength(10);
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-06 empty states are compact', async () => {
    const dom = graphDom();
    const emptyDetail = {
      graphRevision: 'rev-a',
      graphId: 'graph-a',
      item: {
        id: 'node-00000',
        label: 'Empty Detail',
        nodeKind: 'CALLABLE',
        claims: [],
        evidence: [],
        relations: {
          incoming: { totalCount: 0, items: [] },
          outgoing: { totalCount: 0, items: [] }
        }
      }
    };
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return Promise.resolve(emptyDetail);
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();

    const previewText = dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '';
    expect(previewText).toContain('No description available yet');
    expect(previewText).toContain('No claims available');
    expect(previewText).toContain('No evidence available');
    expect(previewText).toContain('No outgoing relationships');
    expect(previewText).toContain('No incoming relationships');
    expect(dom.window.document.querySelectorAll('#knowledgeGraphPreview .knowledge-graph-fact-list article')).toHaveLength(0);
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-07 top metadata layout is compact', async () => {
    const dom = graphDom();
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    const progressText = dom.window.document.getElementById('knowledgeGraphProgress')?.textContent || '';
    expect(dom.window.document.getElementById('knowledgeGraphSourceTitle')?.textContent).toBe('Forge AI');
    expect(dom.window.document.getElementById('knowledgeGraphStatusText')?.textContent).toContain('COMPLETED');
    expect(progressText).toContain('10 / 10 files');
    expect(progressText).toContain('failed 0');
    expect(progressText).toContain('diagnostics 0');
    expect(progressText).toContain('graph available');
    expect(progressText).not.toContain('rev-a');
    expect(dom.window.document.querySelector('.knowledge-graph-metric')).toBeNull();
    expect(dom.window.document.querySelector('.detail-card')).toBeNull();
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-08 right panel scroll is local', async () => {
    const dom = graphDom();
    const manyRelations = Array.from({ length: 40 }, (_, index) => ({
      edgeId: `edge-many-${index}`,
      edgeKind: 'REFERENCES',
      sourceNodeId: 'node-00000',
      sourceName: 'Detail Node',
      targetNodeId: `many-target-${index}`,
      targetName: `RelatedThing${index}`,
      targetKind: 'TYPE',
      sourcePath: 'src/Detail.java',
      lineStart: 30 + index
    }));
    const detail = {
      ...nodeDetailFixture(),
      item: {
        ...nodeDetailFixture().item,
        relations: {
          outgoing: { totalCount: manyRelations.length, items: manyRelations },
          incoming: { totalCount: 0, items: [] }
        }
      }
    };
    const http = {
      get: vi.fn((path: string) => {
        if (path.includes('/metadata')) {
          return Promise.resolve(metadataPayload());
        }
        if (path.includes('/view')) {
          return Promise.resolve(graphViewPayload(2, 2, 'rev-a', 1));
        }
        if (path.includes('/node/node-00000')) {
          return Promise.resolve(detail);
        }
        throw new Error(`unexpected ${path}`);
      })
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();

    const preview = dom.window.document.getElementById('knowledgeGraphPreview') as HTMLElement;
    preview.scrollTop = 24;
    expect(preview.scrollTop).toBe(24);
    expect(preview.textContent).toContain('Outgoing 40');
    expect(preview.textContent).toContain('+35 more');
    expect(dom.window.document.getElementById('knowledgeGraphSvg')).toBeTruthy();
    expect(dom.window.document.getElementById('knowledgeGraphDetails')).toBeNull();
    page.dispose();
  });

  it('UI-GRAPH-DETAIL-UX-10 stale detail response still ignored', async () => {
    const dom = graphDom();
    let resolveNodeA: (value: unknown) => void = () => undefined;
    const nodeADetail = new Promise((resolve) => {
      resolveNodeA = resolve;
    });
    const client = {
      loadGraphData: vi.fn().mockResolvedValue({
        ...manifest(2, 0, 'rev-a'),
        nodes: [node(0), node(1)],
        edges: [],
        meta: { returnedNodeCount: 2, returnedEdgeCount: 0 }
      }),
      loadNodeDetail: vi.fn((nodeId: string) => {
        if (nodeId === 'node-00000') {
          return nodeADetail;
        }
        return Promise.resolve({
          ...nodeDetailFixture('node-00001'),
          item: {
            ...nodeDetailFixture('node-00001').item,
            label: 'Node B Detail',
            evidence: [{ id: 'ev-node-b', text: 'fresh evidence for B', relativePath: 'src/B.java', lineStart: 21 }]
          }
        });
      }),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();

    void page.selectNode('node-00000');
    await flushAsync(1);
    void page.selectNode('node-00001');
    await flushAsync();
    resolveNodeA({
      graphRevision: 'rev-a',
      item: { id: 'node-00000', label: 'Stale Node A', nodeKind: 'CALLABLE', evidence: [{ id: 'stale', text: 'stale evidence' }] }
    });
    await flushAsync();

    const detailText = dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '';
    expect(client.loadNodeDetail).toHaveBeenCalledTimes(2);
    expect(detailText).toContain('Node B Detail');
    expect(detailText).toContain('fresh evidence for B');
    expect(detailText).not.toContain('Stale Node A');
    expect(detailText).not.toContain('stale evidence');
    expect(dom.window.document.getElementById('knowledgeGraphDetails')).toBeNull();
    page.dispose();
  });

  it('UI-GRAPH-NODE-DETAILS-06 detail error shown in right panel without breaking graph', async () => {
    const cases = [
      { status: 404, code: 'GRAPH_NODE_NOT_FOUND' },
      { status: 409, code: 'GRAPH_REVISION_STALE' },
      { status: 400, code: 'GRAPH_ITEM_SCOPE_MISMATCH' },
      { status: 500, code: 'GRAPH_BACKEND_FAILED' }
    ];
    for (const item of cases) {
      const dom = graphDom();
      const error = Object.assign(new Error(item.code), item);
      const client = {
        loadGraphData: vi.fn().mockResolvedValue({
          ...manifest(2, 1, 'rev-a'),
          nodes: [node(0), node(1)],
          edges: [{ ...edge(0), from: 'node-00000', to: 'node-00001' }],
          meta: { returnedNodeCount: 2, returnedEdgeCount: 1 }
        }),
        loadNodeDetail: vi.fn(() => Promise.reject(error)),
        loadEdgeDetail: vi.fn()
      };
      const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
      page.mount();
      await flushAsync();
      await page.selectNode('node-00000');

      const previewText = dom.window.document.getElementById('knowledgeGraphPreview')?.textContent || '';
      const nodeElement = dom.window.document.querySelector('[data-node-id="node-00000"]') as SVGGElement;
      expect(previewText).toContain('Detail load failed');
      expect(previewText).toContain(item.code);
      expect(previewText).toContain(`status ${item.status}`);
      expect(page.state.selectedDetailLoading).toBe(false);
      expect(nodeElement.classList.contains('selected')).toBe(true);
      page.dispose();
    }
  });

  it('UI-GRAPH-NODE-DETAILS-08 Center button still works', async () => {
    const dom = graphDom();
    const client = {
      loadGraphData: vi.fn().mockResolvedValue({
        ...manifest(2, 1, 'rev-a'),
        nodes: [node(0), node(1)],
        edges: [{ ...edge(0), from: 'node-00000', to: 'node-00001' }],
        meta: { returnedNodeCount: 2, returnedEdgeCount: 1 }
      }),
      loadNodeDetail: vi.fn(() => Promise.resolve(nodeDetailFixture())),
      loadEdgeDetail: vi.fn()
    };
    const page = new KnowledgeGraphPage({ document: dom.window.document, window: dom.window, http: { get: vi.fn(() => Promise.resolve(metadataPayload())) }, client, runtimeConfig: { graphPollIntervalMs: 60000 } });
    page.mount();
    await flushAsync();
    void page.selectNode('node-00000');
    await flushAsync();
    const nodeElement = dom.window.document.querySelector('[data-node-id="node-00000"]') as SVGGElement;
    const circle = nodeElement.querySelector('circle') as SVGCircleElement;
    const radiusBefore = circle.getAttribute('r');
    const classBefore = nodeElement.getAttribute('class');
    const centerSpy = vi.spyOn(page, 'centerNode');
    const centerButton = dom.window.document.querySelector('[data-center-node="node-00000"]') as HTMLButtonElement | null;

    centerButton?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));

    const svg = dom.window.document.getElementById('knowledgeGraphSvg') as unknown as SVGSVGElement;
    svg.dispatchEvent(new dom.window.PointerEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true }));
    svg.dispatchEvent(new dom.window.PointerEvent('pointermove', { clientX: 30, clientY: 30, bubbles: true }));
    svg.dispatchEvent(new dom.window.WheelEvent('wheel', { deltaY: -40, clientX: 120, clientY: 120, bubbles: true, cancelable: true }));
    await flushAsync(2);

    expect(circle.getAttribute('r')).toBe(radiusBefore);
    expect(nodeElement.getAttribute('class')).toBe(classBefore);
    expect(centerSpy).toHaveBeenCalledWith('node-00000');
    expect(nodeElement.classList.contains('selected')).toBe(true);
    expect(page.metrics.panEventCount).toBeGreaterThan(0);
    expect(page.metrics.wheelEventCount).toBeGreaterThan(0);
    expect(dom.window.document.getElementById('knowledgeGraphLayout')).toBeTruthy();
    page.dispose();
  });
});
