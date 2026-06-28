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
        <select id="jarvisQueryIntent"><option value="AUTO">AUTO</option></select>
        <input id="jarvisQueryMaxAnchors">
        <input id="jarvisQueryDepth">
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
          intent: 'AUTO',
          matchedSources: [{ sourceId: 'forge-ai', displayName: 'Forge AI', score: 0.95 }],
          anchors: [{
            sourceId: 'forge-ai',
            nodeId: 'n1',
            stableKey: 'src/JarvisGateway.java|CALLABLE|JarvisGateway',
            kind: 'CALLABLE',
            label: 'JarvisGateway',
            score: 1,
            matchReasons: ['NAME_MATCH'],
            content: 'SECRET_SOURCE_CONTENT'
          }],
          nodes: [{ id: 'n1', sourceId: 'forge-ai', label: 'JarvisGateway', content: 'SECRET_SOURCE_CONTENT' }],
          edges: [],
          evidence: [],
          coverage: { matchedSourceCount: 1, anchorCount: 1, nodeCount: 1, edgeCount: 0, evidenceCount: 0 },
          diagnostics: [{ code: 'OK', message: 'No sensitive data' }]
        });
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();

    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'explain';
    (dom.window.document.getElementById('jarvisQueryMaxAnchors') as HTMLInputElement).value = '5';
    (dom.window.document.getElementById('jarvisQueryDepth') as HTMLInputElement).value = '2';
    await page.submitQuery({ preventDefault: () => undefined });
    (dom.window.document.getElementById('jarvisCommandText') as HTMLTextAreaElement).value = 'run';
    await page.submitCommand({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('JarvisGateway');
    expect(text).toContain('src/JarvisGateway.java');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('["bash"');
    expect(text).not.toContain('sleep 0.2');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', expect.objectContaining({ query: 'explain' }), expect.any(Object));
    page.dispose();
  });

  it('renders no-candidates response and does not call Knowledge directly', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn(() => Promise.resolve({ status: 'UP', model: {}, ollama: {}, actions: { count: 0 } })),
      post: vi.fn(() => Promise.resolve({
        status: 'NO_CANDIDATES',
        intent: 'AUTO',
        matchedSources: [],
        anchors: [],
        nodes: [],
        edges: [],
        coverage: { searchedSourceCount: 2, matchedSourceCount: 0, anchorCount: 0, nodeCount: 0, edgeCount: 0, evidenceCount: 0 },
        diagnostics: [{ code: 'NO_GRAPH_CANDIDATES', message: 'No matches' }]
      }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'missing';

    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('No graph candidates found');
    expect(text).toContain('NO_GRAPH_CANDIDATES');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', expect.any(Object), expect.any(Object));
    const calls = http.post.mock.calls as unknown as Array<[string, unknown?, unknown?]>;
    expect(calls.some(([path]) => path.includes('/knowledge/query'))).toBe(false);
    expect(calls.some(([path]) => path.includes(`/jarvis/${'chat'}`))).toBe(false);
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
