import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KnowledgeOverviewPage, deriveKnowledgeSourceAction } from '../src/operator/knowledge-overview-page.js';

function statusPayload(processed = 1, status = 'PARTIAL', analysisOverrides: Record<string, unknown> = {}) {
  const total = Number(analysisOverrides.inventoryFileCount ?? analysisOverrides.totalFiles ?? 10);
  const failed = Number(analysisOverrides.failedFileCount ?? 0);
  const skipped = Number(analysisOverrides.skippedTooLargeFileCount ?? 0);
  const analyzed = Number(analysisOverrides.analyzedFileCount ?? Math.max(0, processed - failed - skipped));
  const pending = Number(analysisOverrides.pendingFileCount ?? Math.max(total - processed, 0));
  const activeJobId = analysisOverrides.activeJobId ?? (['QUEUED', 'RUNNING', 'STOP_REQUESTED'].includes(status) ? 'job-1' : null);
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
          inventoryFileCount: total,
          analyzedFileCount: analyzed,
          processedFileCount: processed,
          failedFileCount: failed,
          skippedTooLargeFileCount: skipped,
          pendingFileCount: pending,
          percent: total > 0 ? Math.round((processed / total) * 1000) / 10 : 0,
          activeJobId,
          ...analysisOverrides
        },
        facts: { symbolCount: 11, relationCount: 12 }
      }
    ],
    activeJob: ['QUEUED', 'RUNNING', 'STOP_REQUESTED'].includes(status)
      ? { jobId: activeJobId, sourceId: 'ntfssox', status }
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
  let reject: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

function sourceAction(dom: JSDOM, action?: string) {
  const selector = action ? `[data-knowledge-action="${action}"]` : '[data-knowledge-action]';
  return dom.window.document.querySelector(selector) as HTMLButtonElement | null;
}

function clickAction(dom: JSDOM, action?: string) {
  const button = sourceAction(dom, action);
  expect(button).toBeTruthy();
  button?.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  return button as HTMLButtonElement;
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

  it('UI-KNOW-REG-05 sends one final analysis build POST for Analyze', async () => {
    const dom = createOverviewDom();
    const start = deferred<unknown>();
    const http = {
      get: vi.fn(() => Promise.resolve(statusPayload(1, 'PARTIAL'))),
      post: vi.fn(() => start.promise)
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const button = dom.window.document.querySelector('.knowledge-source-analysis-button') as HTMLButtonElement;
    button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));

    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Starting...');
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/knowledge/analysis/build', {
      sourceIds: ['ntfssox'],
      groups: [],
      force: false,
      maxFiles: null,
      concurrency: 1,
      selection: 'DEFAULT'
    });
    start.resolve({ accepted: true });
    await flushAsync();
    page.dispose();
  });

  it('UI-KNOW-REG-06 shows Analyze progress and resumes polling', async () => {
    vi.useFakeTimers();
    const dom = createOverviewDom();
    const snapshots = [
      statusPayload(1, 'PARTIAL'),
      statusPayload(2, 'RUNNING'),
      statusPayload(5, 'RUNNING')
    ];
    const http = {
      get: vi.fn(() => Promise.resolve(snapshots.shift() || statusPayload(5, 'RUNNING'))),
      post: vi.fn(() => Promise.resolve({ accepted: true }))
    };
    const page = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 60000 }
    });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const button = dom.window.document.querySelector('.knowledge-source-analysis-button') as HTMLButtonElement;
    button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();
    await page.currentPromise;
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('RUNNING');
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('2 / 10');

    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(http.get).toHaveBeenCalledTimes(3);
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('5 / 10');
    page.dispose();
  });

  it('UI-KNOW-REG-07 renders Analyze errors and does not start active polling', async () => {
    vi.useFakeTimers();
    const dom = createOverviewDom();
    const error = Object.assign(new Error('proxy rejected analysis start'), { code: 'ANALYSIS_START_FAILED', status: 502 });
    const http = {
      get: vi.fn(() => Promise.resolve(statusPayload(1, 'PARTIAL'))),
      post: vi.fn(() => Promise.reject(error))
    };
    const page = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 60000 }
    });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const button = dom.window.document.querySelector('.knowledge-source-analysis-button') as HTMLButtonElement;
    button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(dom.window.document.getElementById('knowledgeAnalysisError')?.textContent).toContain('ANALYSIS_START_FAILED');
    expect(button.disabled).toBe(false);
    expect(button.textContent).toBe('Analyze');
    await vi.advanceTimersByTimeAsync(1999);
    expect(http.get).toHaveBeenCalledTimes(1);
    page.dispose();
  });

  it('UI-KNOW-ACTION-01 not analyzed shows Analyze', async () => {
    const dom = createOverviewDom();
    const snapshots = [
      statusPayload(0, 'NOT_ANALYZED'),
      statusPayload(1, 'RUNNING')
    ];
    const http = {
      get: vi.fn(() => Promise.resolve(snapshots.shift() || statusPayload(1, 'RUNNING'))),
      post: vi.fn(() => Promise.resolve({ accepted: true }))
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const button = sourceAction(dom, 'analyze');
    const timerCount = page.polling.timerCount;
    expect(button?.textContent).toBe('Analyze');

    clickAction(dom, 'analyze');
    await flushAsync();
    await page.currentPromise;
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/knowledge/analysis/build', {
      sourceIds: ['ntfssox'],
      groups: [],
      force: false,
      maxFiles: null,
      concurrency: 1,
      selection: 'DEFAULT'
    });
    expect(page.polling.running).toBe(true);
    expect(page.polling.timerCount).toBeGreaterThan(timerCount);
    page.dispose();
  });

  it('UI-KNOW-ACTION-02 running shows Stop / no duplicate Analyze', async () => {
    const dom = createOverviewDom();
    const http = {
      get: vi.fn(() => Promise.resolve(statusPayload(4, 'RUNNING', { activeJobId: 'job-1' }))),
      post: vi.fn((_path: string, _body?: unknown) => Promise.resolve({ stopped: true }))
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();

    expect(sourceAction(dom, 'analyze')).toBeNull();
    expect(sourceAction(dom, 'stop')?.textContent).toBe('Stop');
    clickAction(dom, 'stop');
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/knowledge/analysis/jobs/job-1/stop', {});
    expect(http.post.mock.calls.some(([path]) => path === '/knowledge/analysis/build')).toBe(false);
    page.dispose();
  });

  it('UI-KNOW-ACTION-03 completed no failures hides Analyze', async () => {
    const dom = createOverviewDom();
    const http = {
      get: vi.fn(() => Promise.resolve(statusPayload(10, 'COMPLETED'))),
      post: vi.fn()
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const actionArea = dom.window.document.querySelector('.knowledge-source-actions') as HTMLElement;
    actionArea.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));

    expect(sourceAction(dom, 'analyze')).toBeNull();
    expect(sourceAction(dom, 'retry-failed')).toBeNull();
    expect(actionArea.textContent).toContain('Complete');
    expect(http.post).not.toHaveBeenCalled();
    expect(deriveKnowledgeSourceAction(statusPayload(10, 'COMPLETED').services[0]).kind).toBe('complete');
    page.dispose();
  });

  it('UI-KNOW-ACTION-04 completed with failures shows Retry Failed', async () => {
    const dom = createOverviewDom();
    const failed = statusPayload(10, 'PARTIAL', {
      analyzedFileCount: 8,
      failedFileCount: 2,
      pendingFileCount: 0
    });
    const http = {
      get: vi.fn(() => Promise.resolve(failed)),
      post: vi.fn()
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();

    expect(sourceAction(dom, 'analyze')).toBeNull();
    expect(sourceAction(dom, 'retry-failed')?.textContent).toBe('Retry Failed');
    expect(dom.window.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('failed 2');
    expect(deriveKnowledgeSourceAction(failed.services[0]).kind).toBe('retry-failed');
    page.dispose();
  });

  it('UI-KNOW-ACTION-05 Retry Failed sends retry endpoint', async () => {
    const dom = createOverviewDom();
    const snapshots = [
      statusPayload(10, 'PARTIAL', { analyzedFileCount: 8, failedFileCount: 2, pendingFileCount: 0 }),
      statusPayload(1, 'RUNNING', { inventoryFileCount: 2, activeJobId: 'retry-job' })
    ];
    const http = {
      get: vi.fn(() => Promise.resolve(snapshots.shift() || statusPayload(1, 'RUNNING', { inventoryFileCount: 2, activeJobId: 'retry-job' }))),
      post: vi.fn((_path: string, _body?: unknown) => Promise.resolve({ jobId: 'retry-job', selection: 'FAILED_ONLY', selectedFileCount: 2 }))
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const timerCount = page.polling.timerCount;
    clickAction(dom, 'retry-failed');
    await flushAsync();
    await page.currentPromise;
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith('/knowledge/analysis/retry-failed', {
      sourceIds: ['ntfssox'],
      concurrency: 1
    });
    expect(http.post.mock.calls.some(([path]) => path === '/knowledge/analysis/build')).toBe(false);
    expect(page.polling.running).toBe(true);
    expect(page.polling.timerCount).toBeGreaterThan(timerCount);
    page.dispose();
  });

  it('UI-KNOW-ACTION-06 Retry Failed no-op/error visible', async () => {
    vi.useFakeTimers();
    const dom = createOverviewDom();
    const http = {
      get: vi.fn(() => Promise.resolve(statusPayload(10, 'PARTIAL', { analyzedFileCount: 8, failedFileCount: 2, pendingFileCount: 0 }))),
      post: vi.fn(() => Promise.resolve({ result: 'NO_FAILED_FILES', status: 'NO_FAILED_FILES', selectedFileCount: 0 }))
    };
    const page = new KnowledgeOverviewPage({
      document: dom.window.document,
      window: dom.window,
      http,
      runtimeConfig: { activeJobPollIntervalMs: 2000, statusPollIntervalMs: 60000 }
    });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const button = clickAction(dom, 'retry-failed');
    expect(button.disabled).toBe(true);
    await flushAsync();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.get).toHaveBeenCalledTimes(1);
    expect(dom.window.document.getElementById('knowledgeAnalysisError')?.textContent).toContain('NO_FAILED_FILES');
    expect(button.disabled).toBe(false);
    expect(button.textContent).toBe('Retry Failed');
    await vi.advanceTimersByTimeAsync(1999);
    expect(http.get).toHaveBeenCalledTimes(1);
    page.dispose();
  });

  it('UI-KNOW-ACTION-07 stale overview after action ignored', async () => {
    const dom = createOverviewDom();
    const stale = deferred<unknown>();
    let getCount = 0;
    const http = {
      get: vi.fn(() => {
        getCount += 1;
        if (getCount === 1) {
          return Promise.resolve(statusPayload(10, 'PARTIAL', { analyzedFileCount: 8, failedFileCount: 2, pendingFileCount: 0 }));
        }
        if (getCount === 2) {
          return stale.promise;
        }
        return Promise.resolve(statusPayload(1, 'RUNNING', { inventoryFileCount: 2, activeJobId: 'retry-job' }));
      }),
      post: vi.fn(() => Promise.resolve({ jobId: 'retry-job', selection: 'FAILED_ONLY', selectedFileCount: 2 }))
    };
    const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

    page.mount();
    await page.currentPromise;
    await flushAsync();
    const staleRequest = page.load({ manual: false });
    await flushAsync();
    clickAction(dom, 'retry-failed');
    await flushAsync();
    await page.currentPromise;
    await flushAsync();

    stale.resolve(statusPayload(10, 'PARTIAL', { analyzedFileCount: 8, failedFileCount: 2, pendingFileCount: 0 }));
    await staleRequest;
    await flushAsync();

    const text = dom.window.document.getElementById('knowledgeSourcesBody')?.textContent || '';
    expect(text).toContain('RUNNING');
    expect(text).not.toContain('Retry Failed');
    page.dispose();
  });

  it('UI-KNOW-ACTION-08 double click guarded', async () => {
    for (const item of [
      { action: 'analyze', status: statusPayload(1, 'PARTIAL'), pending: 'Starting...' },
      { action: 'retry-failed', status: statusPayload(10, 'PARTIAL', { analyzedFileCount: 8, failedFileCount: 2, pendingFileCount: 0 }), pending: 'Retrying...' }
    ]) {
      const dom = createOverviewDom();
      const start = deferred<unknown>();
      const http = {
        get: vi.fn(() => Promise.resolve(item.status)),
        post: vi.fn(() => start.promise)
      };
      const page = new KnowledgeOverviewPage({ document: dom.window.document, window: dom.window, http });

      page.mount();
      await page.currentPromise;
      await flushAsync();
      const button = clickAction(dom, item.action);
      clickAction(dom, item.action);

      expect(button.disabled).toBe(true);
      expect(button.textContent).toBe(item.pending);
      expect(http.post).toHaveBeenCalledTimes(1);
      start.resolve({ accepted: true, selectedFileCount: item.action === 'retry-failed' ? 2 : undefined });
      await flushAsync();
      page.dispose();
    }
  });
});
