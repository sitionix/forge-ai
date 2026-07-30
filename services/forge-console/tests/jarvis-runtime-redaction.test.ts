import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
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
    intent: 'AUTO',
    includeTests: false
  };
}

function humanAnswer(text = 'JarvisGateway answers from compact human context.') {
  return {
    answerLanguage: 'uk',
    answers: [graphAnswer('forge-ai', 'JarvisGateway', text)],
    diagnostics: []
  };
}

function graphAnswer(sourceId: string, entrypoint: string, text: string) {
  return {
    graphId: `graph-${entrypoint}`,
    sources: [sourceId],
    queryEntries: [{ unitId: `${sourceId}:unit:${entrypoint}`, sourceId, root: { qualifiedName: entrypoint, label: entrypoint } }],
    text,
    complete: true,
    diagnostics: []
  };
}

function getResponse(path: string) {
  return Promise.resolve(path === '/jarvis/status' ? { status: 'READY', actions: { count: 0 } } : { providers: [] });
}

describe('Jarvis runtime rendering', () => {
  it('PERF-CON-06 renders human responses without graph internals or command arrays', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn(getResponse),
      post: vi.fn(() => Promise.resolve(humanAnswer('JarvisGateway explains the request in normal language.')))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();

    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'explain';
    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('JarvisGateway explains the request in normal language.');
    expect(text).toContain('JarvisGateway');
    expect(text).not.toContain('forge-ai');
    expect(text).not.toContain('CALLS structure');
    expect(text).not.toContain('flowExplanations');
    expect(text).not.toContain('nodeRef');
    expect(text).not.toContain('SECRET_SOURCE_CONTENT');
    expect(text).not.toContain('["bash"');
    expect(text).not.toContain('sleep 0.2');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', queryPayload('explain'), expect.any(Object));
    expect(http.get.mock.calls.map(([path]) => path)).toEqual(['/jarvis/status', '/knowledge/ai-runtime']);
    page.dispose();
  });

  it('renders controlled query errors and does not call Knowledge directly', async () => {
    const dom = jarvisDom();
    const error = Object.assign(new Error('Jarvis query failed: /api/v1/knowledge/query exceeded context with correlationId abc-123'), {
      code: 'HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED',
      status: 503,
      endpoint: '/api/v1/knowledge/query',
      correlationId: 'abc-123'
    });
    const http = {
      get: vi.fn(getResponse),
      post: vi.fn(() => Promise.reject(error))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    page.mount();
    await flushAsync();
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'missing';

    await page.submitQuery({ preventDefault: () => undefined });

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Request failed');
    expect(text).toContain('The request could not be completed. Please try again.');
    expect(text).not.toContain('HUMAN_ANSWER');
    expect(text).not.toContain('CONTEXT_BUDGET');
    expect(text).not.toContain('Jarvis query failed');
    expect(text).not.toContain('/api/v1');
    expect(text).not.toContain('correlationId');
    expect(text).not.toContain('503');
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', expect.any(Object), expect.any(Object));
    const calls = http.post.mock.calls as unknown as Array<[string, unknown?, unknown?]>;
    expect(calls.some(([path]) => path.includes('/knowledge/query'))).toBe(false);
    expect(calls.some(([path]) => path.includes(`/jarvis/${'chat'}`))).toBe(false);
    page.dispose();
  });

  it('shows loading and one assistant error for failed query', async () => {
    const dom = jarvisDom();
    let rejectRequest: (error: Error) => void = () => undefined;
    const http = {
      get: vi.fn(getResponse),
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
    expect(dom.window.document.getElementById('jarvisQueryError')).toBeNull();
    expect(dom.window.document.querySelectorAll('.jarvis-error-card')).toHaveLength(1);
    expect(dom.window.document.body.textContent).toContain('The request could not be completed. Please try again.');
    expect(dom.window.document.body.textContent).not.toContain('controlled failure');
  });
});
