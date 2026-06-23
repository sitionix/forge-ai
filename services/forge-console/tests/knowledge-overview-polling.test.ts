import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KnowledgeOverviewPage } from '../src/operator/knowledge-overview-page.js';

function statusPayload(processed = 1, status = 'PARTIAL') {
  return {
    services: [
      {
        sourceId: 'ntfssox',
        label: 'Notification Service SOX',
        group: 'backend',
        rootExists: true,
        tags: ['java'],
        inventory: { eligibleFileCount: 10, skippedCount: 2 },
        analysis: {
          status,
          inventoryFileCount: 10,
          analyzedFileCount: processed,
          processedFileCount: processed,
          failedFileCount: 0,
          pendingFileCount: 10 - processed,
          percent: processed * 10,
          activeJobId: status === 'RUNNING' ? 'job-1' : null
        },
        facts: { symbolCount: 11, relationCount: 12 }
      }
    ],
    activeJob: status === 'RUNNING'
      ? { jobId: 'job-1', sourceId: 'ntfssox', status: 'RUNNING' }
      : null
  };
}

function createOverviewDom() {
  const dom = new JSDOM(`<!doctype html>
    <body data-page="knowledge">
      <button id="refreshKnowledge" type="button">Refresh</button>
      <div id="knowledgeError" class="hidden"></div>
      <div id="knowledgeAnalysisError" class="hidden"></div>
      <span id="knowledgeUpdated"></span>
      <table><tbody id="knowledgeSourcesBody"></tbody></table>
      <div id="knowledgeDiagnostics"></div>
    </body>`, { url: 'http://127.0.0.1/operator/knowledge.html' });
  dom.window.setTimeout = globalThis.setTimeout as typeof dom.window.setTimeout;
  dom.window.clearTimeout = globalThis.clearTimeout as typeof dom.window.clearTimeout;
  return dom;
}

function createHttp(resolver: (path: string) => Promise<unknown> | unknown) {
  const calls: string[] = [];
  return {
    calls,
    get: vi.fn((path: string) => {
      calls.push(path);
      return Promise.resolve(resolver(path));
    }),
    post: vi.fn()
  };
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

describe('Knowledge overview modular ownership', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('UI-IT-02 uses one poll owner and does not duplicate timers on remount', async () => {
    vi.useFakeTimers();
    const dom = createOverviewDom();
    const http = createHttp(() => statusPayload(8, 'RUNNING'));
    const first = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 10000 }
    });

    first.mount();
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2000);
    expect(http.get).toHaveBeenCalledTimes(2);
    expect(first.polling.maxConcurrent).toBe(1);
    first.dispose();

    const second = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 10000 }
    });
    second.mount();
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(3);
    dom.window.document.getElementById('refreshKnowledge')?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(4);
    second.dispose();
  });

  it('UI-IT-03 ignores stale overview responses', async () => {
    const dom = createOverviewDom();
    const slow = deferred<unknown>();
    const fast = deferred<unknown>();
    let call = 0;
    const http = createHttp(() => {
      call += 1;
      return call === 1 ? slow.promise : fast.promise;
    });
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    const oldRequest = page.load({ manual: true });
    const newRequest = page.load({ manual: true });
    fast.resolve(statusPayload(9));
    await newRequest;
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('9 / 10');

    slow.resolve(statusPayload(1));
    await oldRequest;
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('9 / 10');
  });

  it('UI-IT-04 ignores overview mutation after dispose', async () => {
    const dom = createOverviewDom();
    const pending = deferred<unknown>();
    const http = createHttp(() => pending.promise);
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });
    const request = page.load({ manual: true });

    page.dispose();
    pending.resolve(statusPayload(5));
    await request;
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).not.toContain('5 / 10');

    const nextHttp = createHttp(() => statusPayload(6));
    const next = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http: nextHttp });
    await next.load({ manual: true });
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('6 / 10');
  });

  it('UI-IT-05 pauses hidden-page polling and resumes safely when visible', async () => {
    vi.useFakeTimers();
    const dom = createOverviewDom();
    const http = createHttp(() => statusPayload(8, 'RUNNING'));
    const page = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 10000 }
    });

    page.mount();
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(1);

    Object.defineProperty(dom.window.document, 'visibilityState', { configurable: true, value: 'hidden' });
    dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(8000);
    expect(http.get).toHaveBeenCalledTimes(1);

    Object.defineProperty(dom.window.document, 'visibilityState', { configurable: true, value: 'visible' });
    dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(2);
    expect(page.polling.maxConcurrent).toBe(1);
  });

  it('UI-IT-06 keeps overview load KPI-only', async () => {
    const dom = createOverviewDom();
    const http = createHttp(() => statusPayload(2));
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    await page.load({ manual: true });
    expect(http.calls).toEqual(['/knowledge/overview']);
    expect(http.calls.some((path) => /analysis\/graph|analysis\/files|analysis\/diagnostics|symbols|relations|source/i.test(path))).toBe(false);
  });
});
