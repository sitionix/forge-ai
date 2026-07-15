import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
      <form id="jarvisQueryForm">
        <textarea id="jarvisQueryText"></textarea>
        <button id="sendJarvisQuery" type="submit">Send</button>
      </form>
      <div id="jarvisQueryLoading" class="hidden"></div>
      <div id="jarvisQueryError" class="hidden"></div>
      <section id="jarvisQueryResult" class="hidden"></section>
      <section id="jarvisQueryDiagnostics" class="hidden"></section>
      <section id="jarvisQueryRaw" class="hidden"></section>
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
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
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

function expectedPayload(queryText: string) {
  return {
    queryText,
    intent: 'AUTO',
    includeTests: false
  };
}

describe('Jarvis human chat', () => {
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

  it('renders only the human answer and compact source footer', async () => {
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
    expect(text).toContain('stsssox');
    expect(text).toContain('SiteController.createSite');
    expect(dom.window.document.querySelectorAll('.jarvis-answer-card')).toHaveLength(1);
    expect(dom.window.document.querySelector('.jarvis-flow-card')).toBeNull();
    expect(text).not.toContain('CALLS');
    expect(text).not.toContain('Technical details');
    expect(text).not.toContain('nodeRef');
    expect(text).not.toContain('Raw JSON');
  });

  it('renders one plain answer card per response item and keeps diagnostics compact', async () => {
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
          code: 'HUMAN_FLOW_ANSWER_GENERATION_FAILED',
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

    const cards = dom.window.document.querySelectorAll('.jarvis-answer-card');
    const cardTexts = Array.from(cards).map((card) => card.textContent ?? '');
    expect(cards).toHaveLength(3);
    expect(cardTexts[0]).toContain('ControllerA.create');
    expect(cardTexts[1]).toContain('ListenerB.handle');
    expect(cardTexts[2]).toContain('JobC.run');
    expect(dom.window.document.body.textContent).toContain('One flow failed.');
    expect(dom.window.document.querySelector('.jarvis-answer-warning')).not.toBeNull();
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
    expect(dom.window.document.querySelectorAll('.jarvis-answer-card')).toHaveLength(1);
    expect(dom.window.document.querySelector('.jarvis-flow-card')).toBeNull();
  });

  it('keeps prior messages when a later request fails', async () => {
    const dom = jarvisDom();
    const http = {
      post: vi.fn()
        .mockResolvedValueOnce(humanResponse('Перша відповідь.'))
        .mockRejectedValueOnce(new Error('Nexus unavailable'))
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
    expect(text).toContain('Nexus unavailable');
    expect(dom.window.document.querySelectorAll('.jarvis-chat-bubble')).toHaveLength(4);
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
