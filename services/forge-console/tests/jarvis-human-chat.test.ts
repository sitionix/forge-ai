import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
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
      <div class="jarvis-security-notice"></div>
      <div id="jarvisCommandResult" class="hidden"></div>
      <div id="jarvisCommandError" class="hidden"></div>
      <form id="jarvisQueryForm">
        <textarea id="jarvisQueryText"></textarea>
        <button id="sendJarvisQuery" type="submit">Send</button>
      </form>
      <div id="jarvisQueryLoading" class="hidden"></div>
      <section id="jarvisQueryResult" class="hidden"></section>
    </body>`, {
    url: 'http://127.0.0.1/operator/jarvis.html',
    pretendToBeVisual: true
  });
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

async function flushAsync() {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve();
  }
}

function submitEvent() {
  return { preventDefault: () => undefined };
}

function humanResponse(text = 'Сайт створюється через SiteController.createSite.') {
  return {
    answerLanguage: 'uk',
    answers: [{ source: 'stsssox', entrypoint: 'SiteController.createSite', text }],
    diagnostics: []
  };
}

function statusResponse() {
  return {
    status: 'READY',
    host: '127.0.0.1',
    port: 9999,
    ollama: { status: 'READY', baseUrl: 'http://127.0.0.1:11434' },
    model: { defaultModel: 'local-model' },
    actions: { count: 2 }
  };
}

function actionsResponse() {
  return {
    actions: [
      { action: 'status', description: 'Checks runtime status.', targets: ['jarvis'] },
      { action: 'model', description: 'Checks model status.', targets: ['ollama'] }
    ]
  };
}

function commandResponse() {
  return {
    intent: { action: 'status', target: 'jarvis' },
    execution: { executed: true, message: 'Runtime is ready.', output: 'ready' }
  };
}

function expectedPayload(queryText: string) {
  return {
    queryText,
    intent: 'AUTO',
    includeTests: false
  };
}

describe('Jarvis human chat', () => {
  it('mount loads runtime status and allowlisted actions', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path === '/jarvis/status' ? statusResponse() : actionsResponse())),
      post: vi.fn()
    };
    const page = new JarvisPage({ document: dom.window.document, http });

    page.mount();
    await flushAsync();

    expect(http.get).toHaveBeenCalledWith('/jarvis/status', expect.any(Object));
    expect(http.get).toHaveBeenCalledWith('/jarvis/actions', expect.any(Object));
    expect(dom.window.document.getElementById('jarvisStatusCards')?.textContent).toContain('Jarvis');
    expect(dom.window.document.getElementById('jarvisStatusCards')?.textContent).toContain('Ollama');
    expect(dom.window.document.getElementById('jarvisStatusCards')?.textContent).toContain('local-model');
    expect(dom.window.document.getElementById('jarvisStatusCards')?.textContent).toContain('allowlisted');
    expect(dom.window.document.getElementById('jarvisUpdated')?.textContent).toContain('updated');
    expect(dom.window.document.getElementById('jarvisActions')?.textContent).toContain('Checks runtime status.');
    expect(dom.window.document.getElementById('jarvisActions')?.textContent).toContain('jarvis');
  });

  it('refresh reloads runtime status and allowlisted actions', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path === '/jarvis/status' ? statusResponse() : actionsResponse())),
      post: vi.fn()
    };
    const page = new JarvisPage({ document: dom.window.document, http });

    page.mount();
    await flushAsync();
    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.Event('click'));
    await flushAsync();

    expect(http.get).toHaveBeenCalledTimes(4);
    expect(http.get.mock.calls.filter(([path]) => path === '/jarvis/status')).toHaveLength(2);
    expect(http.get.mock.calls.filter(([path]) => path === '/jarvis/actions')).toHaveLength(2);
  });

  it('command submission renders command result', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn(),
      post: vi.fn(() => Promise.resolve(commandResponse()))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisCommandText') as HTMLInputElement).value = 'check status';

    await page.submitCommand(submitEvent());

    expect(http.post).toHaveBeenCalledWith('/jarvis/command', { text: 'check status' }, expect.any(Object));
    expect(dom.window.document.getElementById('jarvisCommandResult')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('jarvisCommandResult')?.textContent).toContain('Runtime is ready.');
    expect(dom.window.document.getElementById('jarvisCommandError')?.classList.contains('hidden')).toBe(true);
  });

  it('command errors render technical operator error without using chat safe error', async () => {
    const dom = jarvisDom();
    const error = Object.assign(new Error('Jarvis command unavailable'), { code: 'COMMAND_FAILED', endpoint: '/jarvis/command', status: 502 });
    const http = {
      get: vi.fn(),
      post: vi.fn(() => Promise.reject(error))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisCommandText') as HTMLInputElement).value = 'check status';

    await page.submitCommand(submitEvent());

    const commandError = dom.window.document.getElementById('jarvisCommandError');
    expect(commandError?.classList.contains('hidden')).toBe(false);
    expect(commandError?.textContent).toContain('/jarvis/command');
    expect(commandError?.textContent).toContain('COMMAND_FAILED');
    expect(dom.window.document.querySelectorAll('.jarvis-error-card')).toHaveLength(0);
  });

  it('page disposal removes restored listeners', async () => {
    const dom = jarvisDom();
    const http = {
      get: vi.fn((path: string) => Promise.resolve(path === '/jarvis/status' ? statusResponse() : actionsResponse())),
      post: vi.fn(() => Promise.resolve(commandResponse()))
    };
    const page = new JarvisPage({ document: dom.window.document, http });

    page.mount();
    await flushAsync();
    page.dispose();
    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.Event('click'));
    dom.window.document.getElementById('jarvisCommandForm')?.dispatchEvent(new dom.window.Event('submit'));
    dom.window.document.getElementById('jarvisQueryForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(http.get).toHaveBeenCalledTimes(2);
    expect(http.post).toHaveBeenCalledTimes(0);
  });

  it('submits one human flow request, omits maxFlows, blocks duplicates, and aborts disposal', async () => {
    const dom = jarvisDom();
    const pending = deferred<Record<string, unknown>>();
    let signal: AbortSignal | undefined;
    const http = {
      post: vi.fn((_path: string, _body: unknown, options: { signal?: AbortSignal }) => {
        signal = options.signal;
        return pending.promise;
      })
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'Як створюється сайт?';

    const first = page.submitQuery(submitEvent());
    const second = page.submitQuery(submitEvent());
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/jarvis/query', expectedPayload('Як створюється сайт?'), expect.any(Object));
    const [requestPath, requestPayload] = http.post.mock.calls[0] as [string, unknown, unknown];
    expect(JSON.stringify(requestPayload)).not.toContain('maxFlows');
    expect(requestPath).toBe('/jarvis/query');
    expect((http.post.mock.calls as unknown as Array<[string, unknown, unknown]>).some(([path]) => path.includes('/knowledge'))).toBe(false);
    expect(signal?.aborted).toBe(false);
    await second;

    page.dispose();
    expect(signal?.aborted).toBe(true);
    pending.resolve(humanResponse());
    await first;
  });

  it('renders only the human answer and compact entrypoint title', async () => {
    const dom = jarvisDom();
    const http = { post: vi.fn(() => Promise.resolve(humanResponse())) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'Як створюється сайт?';

    await page.submitQuery(submitEvent());

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Operator');
    expect(text).toContain('Як створюється сайт?');
    expect(text).toContain('Jarvis');
    expect(text).toContain('Сайт створюється через SiteController.createSite.');
    expect(text).toContain('SiteController.createSite');
    expect(text).not.toContain('stsssox');
    expect(dom.window.document.querySelectorAll('.jarvis-answer')).toHaveLength(1);
    expect(dom.window.document.querySelector('.jarvis-answer-card')).toBeNull();
    expect(dom.window.document.querySelector('.jarvis-answer-sources')).toBeNull();
    expect(dom.window.document.querySelector('.jarvis-flow-card')).toBeNull();
    expect(text).not.toContain('CALLS');
    expect(text).not.toContain('Technical details');
    expect(text).not.toContain('nodeRef');
    expect(text).not.toContain('Raw JSON');
    expect(text).not.toContain('Diagnostics');
    expect(text).not.toContain('Scope:');
    expect(text).not.toContain('graph-backed');
    expect(text).not.toContain('grounding');
    expect(text).not.toContain('segment');
    expect(dom.window.document.getElementById('jarvisQueryDiagnostics')).toBeNull();
    expect(dom.window.document.getElementById('jarvisQueryRaw')).toBeNull();
    expect(dom.window.document.getElementById('jarvisQueryError')).toBeNull();
  });

  it('renders one plain answer block per response item and hides normal-chat diagnostics', async () => {
    const dom = jarvisDom();
    const response = {
      answerLanguage: 'uk',
      answers: [
        { source: 'svc', entrypoint: 'ControllerA.create', text: 'Перша відповідь.' },
        { source: 'svc', entrypoint: 'ListenerB.handle', text: 'Друга відповідь.' },
        { source: 'svc', entrypoint: 'JobC.run', text: 'Третя відповідь.' }
      ],
      diagnostics: [
        {
          code: 'FLOW_WALKTHROUGH_LANGUAGE_FALLBACK',
          message: 'One flow failed.',
          sourceId: 'svc',
          metadata: { entrypoint: 'Failed.handle' }
        }
      ]
    };
    const http = { post: vi.fn(() => Promise.resolve(response)) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'Поясни';

    await page.submitQuery(submitEvent());

    const blocks = dom.window.document.querySelectorAll('.jarvis-answer');
    const blockTexts = Array.from(blocks).map((block) => block.textContent ?? '');
    expect(blocks).toHaveLength(3);
    expect(blockTexts[0]).toContain('ControllerA.create');
    expect(blockTexts[1]).toContain('ListenerB.handle');
    expect(blockTexts[2]).toContain('JobC.run');
    expect(dom.window.document.querySelector('.jarvis-answer-card')).toBeNull();
    expect(dom.window.document.querySelector('.jarvis-answer-sources')).toBeNull();
    expect(dom.window.document.body.textContent).not.toContain('One flow failed.');
    expect(dom.window.document.body.textContent).not.toContain('FLOW_WALKTHROUGH_LANGUAGE_FALLBACK');
    expect(dom.window.document.querySelector('.jarvis-answer-warning')).toBeNull();
  });

  it('preserves multiline numbered answers and keeps escaped text plain', async () => {
    const dom = jarvisDom();
    const response = humanResponse('1. POST /api/v1/sites входить у SiteController.createSite.\\n2. <b>HTML</b> лишається текстом.');
    const http = { post: vi.fn(() => Promise.resolve(response)) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'Як створити сайт?';

    await page.submitQuery(submitEvent());

    const answer = dom.window.document.querySelector('.jarvis-answer-text');
    expect(answer?.textContent).toContain('1. POST /api/v1/sites');
    expect(answer?.textContent).toContain('\\n2. <b>HTML</b> лишається текстом.');
    expect(answer?.innerHTML).toContain('&lt;b&gt;HTML&lt;/b&gt;');
    expect(dom.window.document.querySelector('.jarvis-answer-text b')).toBeNull();
    expect(dom.window.document.querySelectorAll('.jarvis-answer')).toHaveLength(1);
    expect(dom.window.document.querySelector('.jarvis-answer-card')).toBeNull();
    expect(dom.window.document.querySelector('.jarvis-flow-card')).toBeNull();
  });

  it('keeps prior messages when a later request fails', async () => {
    const dom = jarvisDom();
    const http = {
      post: vi.fn()
        .mockResolvedValueOnce(humanResponse('Перша відповідь.'))
        .mockRejectedValueOnce(Object.assign(new Error('Jarvis query failed: Nexus unavailable at /api/v1/knowledge/query'), {
          code: 'HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED',
          correlationId: 'abc-123'
        }))
    };
    const page = new JarvisPage({ document: dom.window.document, http });
    const textarea = dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement;

    textarea.value = 'Перше питання';
    await page.submitQuery(submitEvent());
    textarea.value = 'Друге питання';
    await page.submitQuery(submitEvent());

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Перше питання');
    expect(text).toContain('Перша відповідь.');
    expect(text).toContain('Друге питання');
    expect(text).toContain('The request could not be completed. Please try again.');
    expect(text).not.toContain('Nexus unavailable');
    expect(text).not.toContain('HUMAN_ANSWER');
    expect(text).not.toContain('CONTEXT_BUDGET');
    expect(text).not.toContain('Jarvis query failed');
    expect(text).not.toContain('/api/v1');
    expect(text).not.toContain('correlationId');
    expect(dom.window.document.querySelectorAll('.jarvis-chat-bubble')).toHaveLength(4);
    expect(dom.window.document.getElementById('jarvisQueryError')).toBeNull();
  });

  it('renders only safe generic text for raw backend error title objects', async () => {
    const dom = jarvisDom();
    const error = Object.assign(new Error('HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED'), {
      title: 'Human answer',
      message: 'HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED',
      safeMessage: 'HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED',
      code: 'HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED',
      endpoint: '/api/v1/jarvis/query',
      correlationId: 'secret-correlation-id'
    });
    const http = { post: vi.fn(() => Promise.reject(error)) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = 'Explain';

    await page.submitQuery(submitEvent());

    const text = dom.window.document.body.textContent || '';
    expect(text).toContain('Request failed');
    expect(text).toContain('The request could not be completed. Please try again.');
    expect(text).not.toContain('Human answer');
    expect(text).not.toContain('HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED');
    expect(text).not.toContain('/api/v1/jarvis/query');
    expect(text).not.toContain('secret-correlation-id');
    expect(dom.window.document.querySelectorAll('.jarvis-error-card')).toHaveLength(1);
    expect(dom.window.document.getElementById('jarvisQueryError')).toBeNull();
  });

  it('keeps safe presentation strings escaped', async () => {
    const dom = jarvisDom();
    const page = new JarvisPage({ document: dom.window.document, http: { post: vi.fn() } });
    const id = page.queryView.appendPendingAssistant();

    page.queryView.replaceWithSafeError(id, {
      title: '<b>Request failed</b>',
      message: '<img src=x onerror="window.__jarvisXss=1">'
    });

    expect(dom.window.document.querySelector('b')).toBeNull();
    expect(dom.window.document.querySelector('img')).toBeNull();
    expect(dom.window.__jarvisXss).toBeUndefined();
    expect(dom.window.document.body.textContent).toContain('<b>Request failed</b>');
  });

  it('escapes user and model text', async () => {
    const dom = jarvisDom();
    const malicious = '<img src=x onerror="window.__jarvisXss=1"><script>window.__jarvisXss=1</script>';
    const http = { post: vi.fn(() => Promise.resolve(humanResponse(malicious))) };
    const page = new JarvisPage({ document: dom.window.document, http });
    (dom.window.document.getElementById('jarvisQueryText') as HTMLTextAreaElement).value = malicious;

    await page.submitQuery(submitEvent());

    expect(dom.window.document.querySelector('script')).toBeNull();
    expect(dom.window.document.querySelector('img')).toBeNull();
    expect(dom.window.__jarvisXss).toBeUndefined();
    expect(dom.window.document.body.textContent).toContain('<script>window.__jarvisXss=1</script>');
  });
});
