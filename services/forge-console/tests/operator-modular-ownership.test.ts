import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function baseHtml(page: string, body: string) {
  return new JSDOM(`<!doctype html><body data-page="${page}">${body}</body>`, {
    url: `http://127.0.0.1/operator/${page}.html`,
    pretendToBeVisual: true
  });
}

function overviewHtml() {
  return baseHtml('knowledge', `
    <button id="refreshKnowledge"></button>
    <div id="knowledgeError" class="hidden"></div>
    <div id="knowledgeAnalysisError" class="hidden"></div>
    <span id="knowledgeUpdated"></span>
    <table><tbody id="knowledgeSourcesBody"></tbody></table>
    <div id="knowledgeDiagnostics"></div>
  `);
}

function graphHtml() {
  const dom = baseHtml('knowledge-graph', `
    <button id="refreshKnowledgeGraph"></button>
    <button id="forceRefreshKnowledgeGraph"></button>
    <button id="fitKnowledgeGraph"></button>
    <button id="fitKnowledgeGraphTop"></button>
    <div id="knowledgeGraphLoading" class="hidden"></div>
    <div id="knowledgeGraphError" class="hidden"></div>
    <span id="knowledgeGraphUpdated"></span>
    <h1 id="knowledgeGraphSourceTitle"></h1>
    <p id="knowledgeGraphSubtitle"></p>
    <p id="knowledgeGraphStatusText"></p>
    <select id="knowledgeGraphMode"><option value="overview">Overview</option></select>
    <select id="knowledgeGraphFlowDomain"><option value="CODE">Code</option></select>
    <select id="knowledgeGraphDirection"><option value="OUTBOUND">Outbound</option></select>
    <select id="knowledgeGraphDepth"><option value="2">2</option></select>
    <select id="knowledgeGraphExternal"><option value="collapsed">Collapsed</option></select>
    <select id="knowledgeGraphUnresolved"><option value="summarize">Summarize</option></select>
    <select id="knowledgeGraphDensity"><option value="compact">Compact</option></select>
    <select id="knowledgeGraphLabelsMode"><option value="auto">Auto</option></select>
    <select id="knowledgeGraphMaxNodes"><option value="80">80</option></select>
    <select id="knowledgeGraphIsolated"><option value="hide">Hide</option></select>
    <input id="knowledgeGraphAutoRefresh" type="checkbox">
    <input id="knowledgeGraphSearch">
    <button data-graph-tab="overview"></button>
    <section id="knowledgeGraphSummary"></section>
    <svg id="knowledgeGraphSvg"></svg>
    <section id="knowledgeGraphDetails"></section>
  `);
  dom.window.requestAnimationFrame = ((callback: FrameRequestCallback) => setTimeout(() => callback(Date.now()), 0)) as unknown as typeof dom.window.requestAnimationFrame;
  return dom;
}

function jarvisHtml() {
  return baseHtml('jarvis', `
    <button id="refreshJarvis"></button>
    <span id="jarvisUpdated"></span>
    <div id="jarvisStatusError" class="hidden"></div>
    <div id="jarvisActionsError" class="hidden"></div>
    <section id="jarvisStatusCards"></section>
    <section id="jarvisActions"></section>
    <form id="jarvisCommandForm">
      <textarea id="jarvisCommandText"></textarea>
      <button id="executeJarvisCommand" type="submit">Execute</button>
    </form>
    <div id="jarvisCommandError" class="hidden"></div>
    <section id="jarvisCommandResult" class="hidden"></section>
    <form id="jarvisQueryForm">
      <textarea id="jarvisQueryText"></textarea>
      <button id="sendJarvisQuery" type="submit">Send</button>
    </form>
    <div id="jarvisQueryLoading" class="hidden"></div>
    <div id="jarvisQueryError" class="hidden"></div>
    <section id="jarvisQueryResult" class="hidden"></section>
    <section id="jarvisQueryDiagnostics" class="hidden"></section>
    <section id="jarvisQueryRaw" class="hidden"></section>
  `);
}

function statusPayload() {
  return {
    services: [{
      sourceId: 'svc',
      label: 'Service',
      inventory: { eligibleFileCount: 1 },
      analysis: {
        status: 'COMPLETED',
        inventoryFileCount: 1,
        analyzedFileCount: 1,
        processedFileCount: 1,
        failedFileCount: 0,
        pendingFileCount: 0
      }
    }],
    activeJob: null
  };
}

function graphResponses(path: string) {
  if (path.includes('/manifest')) {
    return { status: 200, ok: true, headers: new Headers(), body: { sourceId: 'svc', graphRevision: 'rev', totalNodeCount: 1, totalEdgeCount: 0 } };
  }
  if (path.includes('/nodes')) {
    return { graphRevision: 'rev', items: [{ id: 'n1', label: 'n1', nodeKind: 'CALLABLE' }], complete: true, returnedCount: 1 };
  }
  if (path.includes('/edges')) {
    return { graphRevision: 'rev', items: [], complete: true, returnedCount: 0 };
  }
  throw new Error(path);
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

function queryPayload(queryText: string) {
  return { queryText };
}

describe('Operator Console modular request ownership', () => {
  it('UI-IT-01 mounts the correct page owner only', async () => {
    const overview = overviewHtml();
    const overviewHttp = { get: vi.fn(() => Promise.resolve(statusPayload())), post: vi.fn() };
    bootstrapOperatorConsole({ document: overview.window.document, window: overview.window, http: overviewHttp });
    await flushAsync();
    expect((overview.window.__forgeMountedOperatorPage as any).constructor.name).toBe('KnowledgeOverviewPage');
    expect((overviewHttp.get.mock.calls as unknown as Array<[string]>).map(([path]) => path)).toEqual(['/knowledge/overview']);

    const graph = graphHtml();
    const graphHttp = { get: vi.fn((path: string) => Promise.resolve(graphResponses(path))), post: vi.fn() };
    bootstrapOperatorConsole({ document: graph.window.document, window: graph.window, http: graphHttp });
    await flushAsync();
    expect((graph.window.__forgeMountedOperatorPage as any).constructor.name).toBe('KnowledgeGraphPage');
    expect((graphHttp.get.mock.calls as Array<[string]>).some(([path]) => path.includes('/jarvis/'))).toBe(false);

    const jarvis = jarvisHtml();
    const jarvisHttp = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'READY' } : { actions: [] })),
      post: vi.fn()
    };
    bootstrapOperatorConsole({ document: jarvis.window.document, window: jarvis.window, http: jarvisHttp });
    await flushAsync();
    expect((jarvis.window.__forgeMountedOperatorPage as any).constructor.name).toBe('JarvisPage');
    expect((jarvisHttp.get.mock.calls as Array<[string]>).map(([path]) => path).sort()).toEqual(['/jarvis/actions', '/jarvis/status']);
  });

  it('UI-IT-02 sends no duplicate Jarvis init, refresh, or remount requests', async () => {
    const dom = jarvisHtml();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'READY' } : { actions: [] })),
      post: vi.fn()
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    expect(http.get.mock.calls.map(([path]: [string]) => path).sort()).toEqual(['/jarvis/actions', '/jarvis/status']);

    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    expect(http.get.mock.calls.filter(([path]: [string]) => path === '/jarvis/status')).toHaveLength(2);
    expect(http.get.mock.calls.filter(([path]: [string]) => path === '/jarvis/actions')).toHaveLength(2);

    page.dispose();
    const second = new JarvisPage({ document: dom.window.document, http });
    second.mount();
    await flushAsync();
    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    expect(http.get.mock.calls.filter(([path]: [string]) => path === '/jarvis/status')).toHaveLength(4);
    expect(http.get.mock.calls.filter(([path]: [string]) => path === '/jarvis/actions')).toHaveLength(4);
  });

  it('UI-IT-03 prevents duplicate Jarvis query submissions while loading', async () => {
    const dom = jarvisHtml();
    const slow = deferred<unknown>();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'READY' } : { actions: [] })),
      post: vi.fn(() => slow.promise)
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'hello';

    const first = page.submitQuery(new dom.window.Event('submit'));
    const second = page.submitQuery(new dom.window.Event('submit'));
    await flushAsync();
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('hello'), expect.any(Object));
    await second;
    slow.resolve({ status: 'OK', intent: 'UNKNOWN', matchedSources: [], matchedNodes: [], flowPaths: [], nodes: [], edges: [], coverage: {}, diagnostics: [] });
    await first;
    expect(dom.window.document.getElementById('sendJarvisQuery')?.textContent).toBe('Send');
  });

  it('UI-IT-04 prevents Jarvis DOM mutation after dispose', async () => {
    const dom = jarvisHtml();
    const pending = deferred<unknown>();
    const http = { get: vi.fn(() => pending.promise), post: vi.fn() };
    const page = new JarvisPage({ document: dom.window.document, http });
    const request = page.loadStatus();

    page.dispose();
    pending.resolve({ status: 'READY', host: '127.0.0.1' });
    await request;
    expect(dom.window.document.getElementById('jarvisStatusCards')?.textContent).toBe('');
  });

  it('UI-IT-09 renders Jarvis query preview without source content', async () => {
    const dom = jarvisHtml();
    const http = {
      get: vi.fn(),
      post: vi.fn(() => Promise.resolve({
        status: 'OK',
        intent: 'UNKNOWN',
        matchedSources: [{ sourceId: 'svc', displayName: 'Service', score: 0.9 }],
        matchedNodes: [{
          sourceId: 'svc',
          nodeId: 'n1',
          stableKey: 'src/App.java|CALLABLE|run',
          kind: 'CALLABLE',
          label: 'run',
          score: 0.9,
          matchReasons: ['NAME_MATCH'],
          content: 'SECRET_SOURCE_CONTENT',
          sourceContent: 'ALSO_SECRET'
        }],
        flowPaths: [{
          flowId: 'flow-1',
          sourceId: 'svc',
          nodes: [{ id: 'n1', sourceId: 'svc', label: 'run' }],
          edges: [],
          evidence: [],
          complete: true,
          stopReason: 'TERMINAL_NODE'
        }],
        nodes: [{ id: 'n1', sourceId: 'svc', label: 'run', content: 'SECRET_SOURCE_CONTENT' }],
        edges: [],
        coverage: { matchedSourceCount: 1, matchedNodeCount: 1, flowPathCount: 1, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
        diagnostics: [{ code: 'D1', message: 'diag' }]
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'hello';
    await page.submitQuery(new dom.window.Event('submit'));

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('svc');
    expect(text).toContain('run');
    expect(text).toContain('NAME_MATCH');
    expect(text).toContain('flow-1');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('ALSO_SECRET');
  });
});
