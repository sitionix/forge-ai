import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';

const operatorUiSource = readFileSync(join(process.cwd(), 'src/operator/operator-ui.js'), 'utf8');

type FetchResolver = (value: ResponseLike) => void;

interface ResponseLike {
  ok: boolean;
  status: number;
  statusText: string;
  headers: Headers;
  text: () => Promise<string>;
}

function statusPayload(processed = 1) {
  return {
    services: [
      {
        sourceId: 'ntfssox',
        label: 'Notification Service SOX',
        group: 'backend',
        rootExists: true,
        tags: ['java'],
        inventory: {
          eligibleFileCount: 10,
          skippedCount: 2
        },
        analysis: {
          status: processed === 0 ? 'NOT_ANALYZED' : processed === 10 ? 'COMPLETED' : 'PARTIAL',
          inventoryFileCount: 10,
          analyzedFileCount: processed,
          processedFileCount: processed,
          failedFileCount: 0,
          pendingFileCount: 10 - processed,
          percent: processed * 10,
          activeJobId: null
        },
        facts: {
          symbolCount: 11,
          relationCount: 12
        }
      }
    ],
    activeJob: null
  };
}

function runningStatusPayload() {
  const payload = statusPayload(8) as any;
  const analysis = payload.services[0].analysis;
  analysis.status = 'RUNNING';
  analysis.processedFileCount = 8;
  analysis.pendingFileCount = 0;
  analysis.runningFiles = 2;
  analysis.completedFiles = 8;
  analysis.totalFiles = 10;
  analysis.activeJobId = 'job-1';
  payload.activeJob = {
    jobId: 'job-1',
    sourceId: 'ntfssox',
    status: 'RUNNING',
    selectedFileCount: 2,
    processedFileCount: 0,
    failedFileCount: 0,
    currentRelativePath: 'src/App.java'
  };
  return payload;
}

function jsonResponse(payload: unknown, status = 200): ResponseLike {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'ERROR',
    headers: new Headers(),
    text: () => Promise.resolve(JSON.stringify(payload))
  };
}

function createPage() {
  const dom = new JSDOM(
    `<!doctype html>
      <body data-page="knowledge">
        <button id="refreshKnowledge" type="button">Refresh</button>
        <div id="knowledgeError" class="hidden"></div>
        <div id="knowledgeAnalysisError" class="hidden"></div>
        <span id="knowledgeUpdated"></span>
        <table><tbody id="knowledgeSourcesBody"></tbody></table>
        <div id="knowledgeDiagnostics"></div>
      </body>`,
    {
      runScripts: 'outside-only',
      url: 'http://127.0.0.1:9099/fgaisox/operator/index.html'
    }
  );
  const win = dom.window as typeof dom.window & {
    __FORGE_OPERATOR_TEST_HOOKS__: boolean;
    __forgeKnowledgeOverviewTestApi: {
      state: {
        maxConcurrent: number;
        requestCount: number;
        currentPromise: Promise<unknown> | null;
      };
      requestKnowledgeOverview: (options?: Record<string, unknown>) => Promise<unknown>;
      stopKnowledgeStatusPolling: () => void;
    };
    fetch: ReturnType<typeof vi.fn>;
  };
  win.__FORGE_OPERATOR_TEST_HOOKS__ = true;
  win.FORGE_OPERATOR_RUNTIME_CONFIG = {
    infrastructureApiBasePath: '/api/v1/infrastructure'
  };
  win.setTimeout = globalThis.setTimeout as typeof win.setTimeout;
  win.clearTimeout = globalThis.clearTimeout as typeof win.clearTimeout;
  win.AbortController = globalThis.AbortController;
  return { dom, win };
}

function installFetch(win: ReturnType<typeof createPage>['win'], implementation: Parameters<typeof vi.fn>[0]) {
  win.fetch = vi.fn(implementation) as unknown as typeof win.fetch;
}

function runOperator(win: ReturnType<typeof createPage>['win']) {
  win.eval(operatorUiSource);
  return win.__forgeKnowledgeOverviewTestApi;
}

async function flushAsync() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('Knowledge services status serial polling', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('continues polling two seconds after each successful response', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    installFetch(win, () => Promise.resolve(jsonResponse(statusPayload(win.fetch.mock.calls.length))));

    const api = runOperator(win);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    await api.state.currentPromise;
    await flushAsync();
    expect(win.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('Notification Service SOX');
    expect(api.state.requestCount).toBe(1);

    await vi.advanceTimersByTimeAsync(1999);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    expect(win.fetch).toHaveBeenCalledTimes(2);
    await flushAsync();

    await vi.advanceTimersByTimeAsync(2000);
    expect(win.fetch).toHaveBeenCalledTimes(3);
    expect(api.state.maxConcurrent).toBe(1);
  });

  it('waits for a slow response before starting the two second delay', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    let resolveFirst: FetchResolver = () => {
      throw new Error('First request resolver was not initialized');
    };
    installFetch(win, () => {
      if (win.fetch.mock.calls.length === 1) {
        return new Promise<ResponseLike>((resolve) => {
          resolveFirst = resolve;
        });
      }
      return Promise.resolve(jsonResponse(statusPayload(2)));
    });

    runOperator(win);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(6000);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    resolveFirst(jsonResponse(statusPayload(1)));
    await flushAsync();

    await vi.advanceTimersByTimeAsync(1999);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    expect(win.fetch).toHaveBeenCalledTimes(2);
  });

  it('reuses an active request for manual refresh and graph status consumers', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    let resolveFirst: FetchResolver = () => {
      throw new Error('First request resolver was not initialized');
    };
    installFetch(win, () => new Promise<ResponseLike>((resolve) => {
      resolveFirst = resolve;
    }));

    const api = runOperator(win);
    expect(win.fetch).toHaveBeenCalledTimes(1);

    win.document.getElementById('refreshKnowledge')?.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    void api.requestKnowledgeOverview({ caller: 'knowledge-graph-auto' });
    await flushAsync();

    expect(win.fetch).toHaveBeenCalledTimes(1);
    expect(api.state.maxConcurrent).toBe(1);

    resolveFirst(jsonResponse(statusPayload(1)));
    await flushAsync();

    await vi.advanceTimersByTimeAsync(2000);
    expect(win.fetch).toHaveBeenCalledTimes(2);
  });

  it('keeps the last table and does not rapidly retry after an error', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    installFetch(win, () => {
      const call = win.fetch.mock.calls.length;
      if (call === 1) {
        return Promise.resolve(jsonResponse(statusPayload(3)));
      }
      if (call === 2) {
        return Promise.resolve(jsonResponse({ message: 'failed' }, 500));
      }
      return Promise.resolve(jsonResponse(statusPayload(4)));
    });

    runOperator(win);
    await win.__forgeKnowledgeOverviewTestApi.state.currentPromise;
    await flushAsync();
    const rendered = win.document.getElementById('knowledgeSourcesBody')?.innerHTML || '';

    await vi.advanceTimersByTimeAsync(2000);
    await win.__forgeKnowledgeOverviewTestApi.state.currentPromise?.catch(() => null);
    await flushAsync();
    expect(win.fetch).toHaveBeenCalledTimes(2);
    expect(win.document.getElementById('knowledgeSourcesBody')?.innerHTML).toBe(rendered);

    await vi.advanceTimersByTimeAsync(10000);
    expect(win.fetch).toHaveBeenCalledTimes(2);

    win.document.getElementById('refreshKnowledge')?.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    await win.__forgeKnowledgeOverviewTestApi.state.currentPromise;
    await flushAsync();
    expect(win.fetch).toHaveBeenCalledTimes(3);
    expect(win.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('4 / 10');
  });

  it('accepts backend running semantics where pending is not total minus processed', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    installFetch(win, () => Promise.resolve(jsonResponse(runningStatusPayload())));

    const api = runOperator(win);
    await api.state.currentPromise;
    await flushAsync();

    expect(win.document.getElementById('knowledgeError')?.classList.contains('hidden')).toBe(true);
    expect(win.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('RUNNING');
    expect(win.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('8 / 10');
  });

  it('renders a terminal error state when the initial status payload is invalid', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    installFetch(win, () => Promise.resolve(jsonResponse({ services: [{ label: 'missing source' }], activeJob: null })));

    const api = runOperator(win);
    await api.state.currentPromise?.catch(() => null);
    await flushAsync();

    expect(win.document.getElementById('knowledgeError')?.classList.contains('hidden')).toBe(false);
    expect(win.document.getElementById('knowledgeError')?.textContent).toContain('KNOWLEDGE_STATUS_SNAPSHOT_REJECTED');
    expect(win.document.getElementById('knowledgeSourcesBody')?.textContent).toContain('Unable to load services.');
    await vi.advanceTimersByTimeAsync(4000);
    expect(win.fetch).toHaveBeenCalledTimes(1);
  });

  it('does not schedule another request after polling is stopped', async () => {
    vi.useFakeTimers();
    const { win } = createPage();
    installFetch(win, () => Promise.resolve(jsonResponse(statusPayload(1))));

    const api = runOperator(win);
    await flushAsync();
    api.stopKnowledgeStatusPolling();

    await vi.advanceTimersByTimeAsync(4000);
    expect(win.fetch).toHaveBeenCalledTimes(1);
  });
});
