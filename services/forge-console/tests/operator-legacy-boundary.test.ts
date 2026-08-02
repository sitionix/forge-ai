import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';

const testDir = dirname(fileURLToPath(import.meta.url));
const operatorDir = resolve(testDir, '../src/operator');

async function operatorSource(file: string) {
  return readFile(resolve(operatorDir, file), 'utf8');
}

function inScopeDom(page: string, body = '') {
  return new JSDOM(`<!doctype html><body data-page="${page}">${body}</body>`, {
    url: `http://127.0.0.1/operator/${page}.html`,
    pretendToBeVisual: true,
    runScripts: 'outside-only'
  });
}

async function runLegacy(dom: JSDOM) {
  const source = await operatorSource('operator-legacy-ui.js');
  dom.window.eval(source);
}

function overviewBody() {
  return `
    <button id="refreshKnowledge"></button>
    <div id="knowledgeError" class="hidden"></div>
    <div id="knowledgeAnalysisError" class="hidden"></div>
    <span id="knowledgeUpdated"></span>
    <table><tbody id="knowledgeSourcesBody"></tbody></table>
    <div id="knowledgeDiagnostics"></div>
  `;
}

function graphBody() {
  return `
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
  `;
}

function jarvisBody() {
  return `
    <button id="refreshJarvis" type="button">Refresh</button>
    <span id="jarvisUpdated">loading</span>
    <button id="editAiRuntime" type="button">Edit</button>
    <div id="jarvisStatusCards"></div>
    <div id="jarvisStatusError" class="hidden"></div>
    <div id="aiRuntimeError" class="hidden"></div>
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
    <dialog id="aiRuntimeDialog">
      <button id="closeAiRuntimeDialog" type="button">×</button>
      <button id="cancelAiRuntimeDialog" type="button">Cancel</button>
      <div id="aiRuntimeModalError" class="hidden"></div>
      <div id="aiRuntimeProviderOptions"></div>
      <div id="aiRuntimeModelOptions"></div>
      <section id="aiRuntimeEffortSection" class="hidden"><div id="aiRuntimeEffortOptions"></div></section>
    </dialog>
  `;
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

function jarvisRuntimeResponse(path: string) {
  if (path === '/jarvis/status') {
    return { status: 'READY' };
  }
  if (path === '/knowledge/active-profile') {
    return {
      revision: 1,
      llmProfile: { providerId: 'ollama', modelId: 'local-model', effort: null },
      usage: null
    };
  }
  return { providers: [{ providerId: 'ollama', displayName: 'Ollama', status: 'READY', models: [{ modelId: 'local-model', displayName: 'Local Model' }] }] };
}

function graphResponse(path: string) {
  if (path.includes('/view')) {
    return {
      sourceId: 'svc',
      graphRevision: 'rev',
      queryFingerprint: 'fingerprint-rev',
      selectionPolicy: 'RELATIONSHIP_AWARE',
      maxNodes: 80,
      nodes: [{ id: 'n1', label: 'n1', nodeKind: 'CALLABLE' }],
      edges: [],
      totalMatchingNodeCount: 1,
      totalMatchingEdgeCount: 0,
      visibleNodeCount: 1,
      visibleEdgeCount: 0,
      hiddenNodeCount: 0,
      hiddenEdgeCount: 0,
      hasMore: false
    };
  }
  throw new Error(path);
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Operator legacy boundary', () => {
  it('UI-LEGACY-01 does not initialize in-scope pages', async () => {
    for (const [page, body] of [
      ['knowledge', overviewBody()],
      ['knowledge-graph', graphBody()],
      ['jarvis', jarvisBody()]
    ] as const) {
      const dom = inScopeDom(page, body);
      const originalBody = dom.window.document.body.innerHTML;
      const fetchSpy = vi.fn(() => Promise.reject(new Error('legacy fetch must not run')));
      Object.defineProperty(dom.window, 'fetch', { value: fetchSpy, configurable: true });
      const addSpy = vi.spyOn(dom.window.EventTarget.prototype, 'addEventListener');
      const timeoutSpy = vi.spyOn(dom.window, 'setTimeout');
      const intervalSpy = vi.spyOn(dom.window, 'setInterval');

      await runLegacy(dom);

      expect(dom.window.document.body.innerHTML).toBe(originalBody);
      expect(fetchSpy).not.toHaveBeenCalled();
      expect(addSpy).not.toHaveBeenCalled();
      expect(timeoutSpy).not.toHaveBeenCalled();
      expect(intervalSpy).not.toHaveBeenCalled();
      expect(dom.window.__forgeKnowledgeOverviewTestApi).toBeUndefined();
      expect(dom.window.__forgeKnowledgeGraphRuntime).toBeUndefined();
    }
  });

  it('UI-LEGACY-02 keeps in-scope route and owner strings out of legacy production code', async () => {
    const source = await operatorSource('operator-legacy-ui.js');
    [
      '/api/v1/infrastructure',
      '/jarvis/status',
      '/jarvis/actions',
      '/jarvis/command',
      `/jarvis/${'chat'}`,
      '/jarvis/query',
      '/knowledge/overview',
      '/knowledge/analysis',
      '/analysis/symbols',
      '/analysis/relations',
      '/analysis/graph/slice'
    ].forEach((forbidden) => expect(source).not.toContain(forbidden));
    expect(source).not.toMatch(/loadJarvis|submitJarvis|renderJarvis|knowledgeOverview|KnowledgeGraph|knowledgeGraphState/);
    expect(source).not.toMatch(/if\s*\(page === ['"](jarvis|knowledge|knowledge-graph)['"]/);
  });

  it('UI-LEGACY-03 keeps raw fetch out of in-scope modules outside the shared HTTP client', async () => {
    const files = [
      'operator-ui.js',
      'operator-bootstrap.js',
      'operator-router.js',
      'knowledge-overview-page.js',
      'knowledge-graph-page.js',
      'knowledge-graph-client.js',
      'ai-runtime-view.js',
      'jarvis-page.js'
    ];
    for (const file of files) {
      await expect(operatorSource(file)).resolves.not.toMatch(/\bfetch\s*\(/);
    }
    await expect(operatorSource('infrastructure-http-client.js')).resolves.toMatch(/\bfetcher\s*\(/);
  });

  it('UI-LEGACY-04 does not double-attach listeners or requests before modular mount', async () => {
    const jarvis = inScopeDom('jarvis', jarvisBody());
    await runLegacy(jarvis);
    const jarvisHttp = {
      get: vi.fn((path: string) => Promise.resolve(jarvisRuntimeResponse(path))),
      post: vi.fn()
    };
    bootstrapOperatorConsole({ document: jarvis.window.document, window: jarvis.window, http: jarvisHttp });
    await flushAsync();
    expect((jarvisHttp.get.mock.calls as Array<[string]>).map(([path]) => path)).toEqual([
      '/jarvis/status',
      '/knowledge/active-profile',
      '/knowledge/ai-runtime'
    ]);

    const overview = inScopeDom('knowledge', overviewBody());
    await runLegacy(overview);
    const overviewHttp = { get: vi.fn(() => Promise.resolve(statusPayload())), post: vi.fn() };
    bootstrapOperatorConsole({ document: overview.window.document, window: overview.window, http: overviewHttp });
    await flushAsync();
    expect((overview.window.__forgeMountedOperatorPage as any).constructor.name).toBe('KnowledgeOverviewPage');
    expect((overviewHttp.get.mock.calls as unknown as Array<[string]>).map(([path]) => path)).toEqual(['/knowledge/overview']);

    const graph = inScopeDom('knowledge-graph', graphBody());
    graph.window.requestAnimationFrame = ((callback: FrameRequestCallback) => setTimeout(() => callback(Date.now()), 0)) as unknown as typeof graph.window.requestAnimationFrame;
    await runLegacy(graph);
    const graphHttp = { get: vi.fn((path: string) => Promise.resolve(graphResponse(path))), post: vi.fn() };
    bootstrapOperatorConsole({ document: graph.window.document, window: graph.window, http: graphHttp });
    await flushAsync();
    expect((graph.window.__forgeMountedOperatorPage as any).constructor.name).toBe('KnowledgeGraphPage');
    const graphPaths = (graphHttp.get.mock.calls as Array<[string]>).map(([path]) => path);
    expect(graphPaths.filter((path) => path.includes('/knowledge/analysis/graph/view'))).toHaveLength(1);
    expect(graphPaths.some((path) => /analysis\/symbols|analysis\/relations|analysis\/graph\/slice|analysis\/graph($|\?)/.test(path))).toBe(false);
  });

  it('UI-LEGACY-05 keeps modular production wiring and existing Task 03 gates active', async () => {
    const [jarvis, knowledge, graph, legacy] = await Promise.all([
      operatorSource('jarvis.html'),
      operatorSource('knowledge.html'),
      operatorSource('knowledge-graph.html'),
      operatorSource('agents.html')
    ]);
    expect(jarvis).toContain('type="module" src="./operator-ui.js"');
    expect(jarvis).toContain('refreshJarvis');
    expect(jarvis).toContain('jarvisStatusCards');
    expect(jarvis).toContain('jarvisUpdated');
    expect(jarvis).toContain('jarvisStatusError');
    expect(jarvis).toContain('editAiRuntime');
    expect(jarvis).toContain('aiRuntimeDialog');
    expect(jarvis).toContain('aiRuntimeProviderOptions');
    expect(jarvis).not.toContain('jarvisActions');
    expect(jarvis).not.toContain('jarvisActionsError');
    expect(jarvis).not.toContain('Allowlisted Actions');
    expect(jarvis).toContain('jarvisCommandForm');
    expect(jarvis).toContain('jarvisCommandText');
    expect(jarvis).toContain('executeJarvisCommand');
    expect(jarvis).toContain('jarvisCommandResult');
    expect(jarvis).toContain('jarvisCommandError');
    expect(jarvis).toContain('jarvisQueryForm');
    expect(jarvis).toContain('sendJarvisQuery');
    expect(jarvis).toContain('Graph Knowledge Query');
    expect(jarvis).not.toContain('Scope: Auto');
    expect(jarvis).not.toContain('jarvisQueryDiagnostics');
    expect(jarvis).not.toContain('jarvisQueryRaw');
    expect(jarvis).not.toContain('jarvisQueryError');
    expect(jarvis).not.toContain('graph-backed');
    expect(jarvis).not.toContain('jarvisQueryMaxAnchors');
    expect(jarvis).not.toContain('jarvisQueryDepth');
    expect(jarvis).not.toContain('Max anchors');
    expect(knowledge).toContain('type="module" src="./operator-ui.js"');
    expect(graph).toContain('type="module" src="./operator-ui.js"');
    expect(legacy).toContain('src="./operator-legacy-ui.js"');

    const task03Tests = await Promise.all([
      readFile(resolve(testDir, 'operator-modular-ownership.test.ts'), 'utf8'),
      readFile(resolve(testDir, 'knowledge-overview-polling.test.ts'), 'utf8'),
      readFile(resolve(testDir, 'knowledge-graph-visual-contract.test.ts'), 'utf8')
    ]);
    for (const gate of ['UI-IT-01', 'UI-IT-02', 'UI-IT-03', 'UI-IT-04', 'UI-IT-05', 'UI-IT-06', 'UI-IT-07', 'UI-IT-08', 'UI-IT-09']) {
      expect(task03Tests.some((source) => source.includes(gate))).toBe(true);
    }
  });
});
