import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
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
    </body>`, { url: 'http://127.0.0.1/operator/jarvis.html' });
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

function queryPayload(queryText: string) {
  return {
    queryText,
    intent: 'UNKNOWN',
    answerLanguage: 'en',
    includeTests: false,
    maxFlows: 10
  };
}

describe('Jarvis runtime rendering', () => {
  it('PERF-CON-06 renders Jarvis responses without source content or command arrays', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'UP', model: {}, ollama: {}, actions: { count: 1 } } : { actions: [] })),
      post: vi.fn((path: string) => {
        if (path.endsWith('/command')) {
          return Promise.resolve({
            intent: { action: 'safe', target: 'run', arguments: {} },
            execution: { executed: true, message: 'Action executed: safe.run', output: 'done' }
          });
        }
        return Promise.resolve({
          status: 'OK',
          intent: 'UNKNOWN',
          matchedSources: [{ sourceId: 'forge-ai', displayName: 'Forge AI', score: 0.95 }],
          matchedNodes: [{
            sourceId: 'forge-ai',
            nodeId: 'n1',
            stableKey: 'src/JarvisGateway.java|CALLABLE|JarvisGateway',
            kind: 'CALLABLE',
            label: 'JarvisGateway',
            score: 1,
            matchReasons: ['NAME_MATCH'],
            content: 'SECRET_SOURCE_CONTENT'
          }],
          flowPaths: [{
            flowId: 'flow-1',
            sourceId: 'forge-ai',
            matchedNodeIds: ['n2'],
            nodeIds: ['n1', 'n2', 'n3'],
            edgeIds: ['e1', 'e2'],
            boundaryEdgeIds: [],
            evidenceIds: [],
            nodes: [
              { id: 'n1', sourceId: 'forge-ai', label: 'Controller.create' },
              { id: 'n2', sourceId: 'forge-ai', label: 'UseCase.execute' },
              { id: 'n3', sourceId: 'forge-ai', label: 'Repository.save' }
            ],
            edges: [
              { id: 'e1', sourceId: 'forge-ai', fromNodeId: 'n1', toNodeId: 'n2', edgeType: 'CALLS' },
              { id: 'e2', sourceId: 'forge-ai', fromNodeId: 'n2', toNodeId: 'n3', edgeType: 'CALLS' }
            ],
            evidence: [],
            complete: true,
            stopReason: 'TERMINAL_NODE'
          }],
          nodes: [{ id: 'n1', sourceId: 'forge-ai', label: 'JarvisGateway', content: 'SECRET_SOURCE_CONTENT' }],
          edges: [],
          evidence: [],
          coverage: { matchedSourceCount: 1, matchedNodeCount: 1, flowPathCount: 1, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
          diagnostics: [{ code: 'OK', message: 'No sensitive data' }]
        });
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();

    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'explain';
    await page.submitQuery({ preventDefault: () => undefined });
    (dom.window.document.getElementById('jarvisCommandText') as HTMLTextAreaElement).value = 'run';
    await page.submitCommand({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('JarvisGateway');
    expect(text).toContain('src/JarvisGateway.java');
    expect(text).toContain('Flow Paths (1)');
    expect(text).toContain('Controller.create -> UseCase.execute -> Repository.save');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('["bash"');
    expect(text).not.toContain('sleep 0.2');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('explain'), expect.any(Object));
    page.dispose();
  });

  it('renders no-candidates response and does not call Knowledge directly', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn(() => Promise.resolve({ status: 'UP', model: {}, ollama: {}, actions: { count: 0 } })),
      post: vi.fn(() => Promise.resolve({
        status: 'NO_CANDIDATES',
        intent: 'UNKNOWN',
        matchedSources: [],
        matchedNodes: [],
        flowPaths: [],
        nodes: [],
        edges: [],
        coverage: { searchedSourceCount: 2, matchedSourceCount: 0, matchedNodeCount: 0, flowPathCount: 0, nodeCount: 0, edgeCount: 0, evidenceCount: 0 },
        diagnostics: [{ code: 'NO_GRAPH_CANDIDATES', message: 'No matches' }]
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'missing';

    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('No graph matches found');
    expect(text).toContain('NO_GRAPH_CANDIDATES');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', expect.any(Object), expect.any(Object));
    const calls = http.post.mock.calls as unknown as Array<[string, unknown?, unknown?]>;
    expect(calls.some(([path]) => path.includes('/knowledge/query'))).toBe(false);
    expect(calls.some(([path]) => path.includes(`/jarvis/${'chat'}`))).toBe(false);
    page.dispose();
  });

  it('renders flow extraction diagnostics when matched nodes have no paths', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn(() => Promise.resolve({ status: 'UP', model: {}, ollama: {}, actions: { count: 0 } })),
      post: vi.fn(() => Promise.resolve({
        status: 'OK',
        intent: 'UNKNOWN',
        matchedSources: [{ sourceId: 'svc', displayName: 'Service', score: 1 }],
        matchedNodes: [{
          sourceId: 'svc',
          nodeId: 'n1',
          stableKey: 'svc|n1',
          kind: 'CALLABLE',
          label: 'Lonely.execute',
          score: 1,
          matchReasons: ['NAME_MATCH']
        }],
        flowPaths: [],
        nodes: [{ id: 'n1', sourceId: 'svc', label: 'Lonely.execute' }],
        edges: [],
        evidence: [],
        coverage: { matchedSourceCount: 1, matchedNodeCount: 1, flowPathCount: 0, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
        diagnostics: [{ code: 'NO_CALLS_PATH', message: 'No verified CALLS path could be built.' }]
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'lonely';

    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Flow Paths (0)');
    expect(text).toContain('No verified CALLS path could be built.');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('lonely'), expect.any(Object));
    page.dispose();
  });

  it('shows loading and controlled error state for failed query', async () => {
    const dom = jarvisDom();
    let rejectRequest: (error: Error) => void = () => undefined;
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/status') ? { status: 'UP', model: {}, ollama: {}, actions: { count: 0 } } : { actions: [] })),
      post: vi.fn(() => new Promise((_resolve, reject) => {
        rejectRequest = reject;
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'JarvisGateway';

    const request = page.submitQuery({ preventDefault: () => undefined });
    await flushAsync();
    expect(dom.window.document.getElementById('sendJarvisQuery')?.getAttribute('disabled')).not.toBeNull();
    expect(dom.window.document.getElementById('jarvisQueryLoading')?.className).not.toContain('hidden');

    rejectRequest(new Error('controlled failure'));
    await request;

    expect(dom.window.document.getElementById('sendJarvisQuery')?.getAttribute('disabled')).toBeNull();
    expect(dom.window.document.getElementById('jarvisQueryLoading')?.className).toContain('hidden');
    expect(dom.window.document.getElementById('jarvisQueryError')?.textContent).toContain('controlled failure');
  });
});
