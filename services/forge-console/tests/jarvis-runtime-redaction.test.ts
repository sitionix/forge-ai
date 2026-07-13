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
  return { queryText };
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
            nodeKind: 'CALLABLE',
            label: 'JarvisGateway',
            score: 1,
            matchReasons: ['NAME_MATCH'],
            relativePath: 'src/JarvisGateway.java',
            content: 'SECRET_SOURCE_CONTENT'
          }],
          flows: [{
            flowIndex: 1,
            source: 'forge-ai',
            entrypoint: { nodeRef: 'n1', label: 'Controller.create', kind: 'CALLABLE' },
            entrypointOrigin: 'EXPLICIT_GRAPH_FACT',
            matchedAnchors: [],
            nodes: [
              { nodeRef: 'n1', label: 'Controller.create', kind: 'CALLABLE' },
              { nodeRef: 'n2', label: 'UseCase.execute', kind: 'CALLABLE' },
              { nodeRef: 'n3', label: 'Repository.save', kind: 'CALLABLE' }
            ],
            transitions: [
              { transitionRef: 't1', fromNodeRef: 'n1', toNodeRef: 'n2' },
              { transitionRef: 't2', fromNodeRef: 'n2', toNodeRef: 'n3' }
            ],
            boundaries: [], evidence: [],
            complete: true,
            coverage: {}, diagnostics: []
          }],
          coverage: { matchedSourceCount: 1, matchedNodeCount: 1, flowCount: 1, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
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
    expect(text).toContain('Entrypoint Flows (1)');
    expect(text).toContain('Controller.create, UseCase.execute, Repository.save');
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
        flows: [],
        coverage: { searchedSourceCount: 2, matchedSourceCount: 0, matchedNodeCount: 0, flowCount: 0, nodeCount: 0, edgeCount: 0, evidenceCount: 0 },
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
          nodeKind: 'CALLABLE',
          label: 'Lonely.execute',
          score: 1,
          matchReasons: ['NAME_MATCH']
        }],
        flows: [],
        coverage: { matchedSourceCount: 1, matchedNodeCount: 1, flowCount: 0, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
        diagnostics: [{ code: 'ENTRYPOINT_FLOW_ROOT_NOT_FOUND', message: 'No bounded entrypoint root could be built.' }]
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'lonely';

    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Entrypoint Flows (0)');
    expect(text).toContain('No bounded entrypoint root could be built.');
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
