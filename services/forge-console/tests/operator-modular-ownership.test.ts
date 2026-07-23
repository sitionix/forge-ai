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
    <button id="refreshJarvis" type="button">Refresh</button>
    <span id="jarvisUpdated">loading</span>
    <div id="jarvisStatusCards"></div>
    <div id="jarvisStatusError" class="hidden"></div>
    <div id="jarvisActions"></div>
    <div id="jarvisActionsError" class="hidden"></div>
    <form id="jarvisCommandForm">
      <input id="jarvisCommandText" type="text">
      <button id="executeJarvisCommand" type="submit">Execute</button>
    </form>
    <div id="jarvisCommandResult" class="hidden"></div>
    <div id="jarvisCommandError" class="hidden"></div>
    <form id="jarvisQueryForm">
      <textarea id="jarvisQueryText"></textarea>
      <button id="sendJarvisQuery" type="submit">Send</button>
    </form>
    <div id="jarvisQueryLoading" class="hidden"></div>
    <section id="jarvisQueryResult" class="hidden"></section>
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
  return {
    queryText,
    intent: 'AUTO',
    includeTests: false
  };
}

function humanAnswer(text = 'JarvisGateway handles the request.') {
  return {
    answerLanguage: 'uk',
    answers: [{ source: 'svc', entrypoint: 'run', text }],
    diagnostics: []
  };
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
    expect((jarvisHttp.get.mock.calls as Array<[string]>).map(([path]) => path)).toEqual(['/jarvis/status', '/jarvis/actions']);
  });

  it('UI-IT-02 sends only restored Jarvis init requests from the chat page', async () => {
    const dom = jarvisHtml();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'READY' } : { actions: [] })),
      post: vi.fn()
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    expect((http.get.mock.calls as Array<[string]>).map(([path]) => path)).toEqual(['/jarvis/status', '/jarvis/actions']);

    page.dispose();
    const second = new JarvisPage({ document: dom.window.document, http });
    second.mount();
    await flushAsync();
    expect((http.get.mock.calls as Array<[string]>).map(([path]) => path)).toEqual([
      '/jarvis/status',
      '/jarvis/actions',
      '/jarvis/status',
      '/jarvis/actions'
    ]);
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
    slow.resolve(humanAnswer());
    await first;
    expect(dom.window.document.getElementById('sendJarvisQuery')?.textContent).toBe('Send');
  });

  it('UI-IT-04 prevents Jarvis query DOM mutation after dispose', async () => {
    const dom = jarvisHtml();
    const pending = deferred<unknown>();
    const http = { get: vi.fn(), post: vi.fn(() => pending.promise) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'hello';
    const request = page.submitQuery(new dom.window.Event('submit'));
    await flushAsync();

    page.dispose();
    pending.resolve(humanAnswer());
    await request;
    expect(dom.window.document.querySelector('.jarvis-answer-card')).toBeNull();
  });

  it('UI-IT-05 aborts an in-flight Jarvis query when the page is disposed', async () => {
    const dom = jarvisHtml();
    const pending = deferred<unknown>();
    let querySignal: AbortSignal | undefined;
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'READY' } : { actions: [] })),
      post: vi.fn((_path: string, _body: unknown, options: { signal?: AbortSignal }) => {
        querySignal = options.signal;
        return pending.promise;
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'hello';

    const request = page.submitQuery(new dom.window.Event('submit'));
    await flushAsync();
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('hello'), expect.any(Object));
    expect(querySignal?.aborted).toBe(false);

    page.dispose();
    expect(querySignal?.aborted).toBe(true);
    pending.resolve(humanAnswer());
    await request;
    expect(dom.window.document.getElementById('sendJarvisQuery')?.textContent).toBe('Sending...');
  });

  it('UI-IT-09 renders Jarvis human query response without source content', async () => {
    const dom = jarvisHtml();
    const http = {
      get: vi.fn(),
      post: vi.fn(() => Promise.resolve({
        answerLanguage: 'uk',
        answers: [{ source: 'svc', entrypoint: 'run', text: 'The run entrypoint was found without exposing raw source content.' }],
        diagnostics: []
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'hello';
    await page.submitQuery(new dom.window.Event('submit'));

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('run');
    expect(text).toContain('The run entrypoint was found');
    expect(text).not.toContain('svc');
    expect(text).not.toContain('Technical details');
    expect(text).not.toContain('EXPLICIT_GRAPH_FACT');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('ALSO_SECRET');
  });
});
