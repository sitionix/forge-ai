import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { JarvisPage } from '../src/operator/jarvis-page.js';

const testDir = dirname(fileURLToPath(import.meta.url));

function jarvisDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="jarvis">
      <main class="shell">
        <button id="refreshJarvis" type="button">Refresh</button>
        <section class="panel jarvis-status-panel">
          <button id="editAiRuntime" type="button">Edit</button>
          <span id="jarvisUpdated">loading</span>
          <div id="jarvisStatusCards" class="jarvis-status-grid"></div>
          <div id="jarvisStatusError" class="error-box hidden"></div>
          <div id="aiRuntimeError" class="error-box hidden"></div>
        </section>
        <form id="jarvisCommandForm"><input id="jarvisCommandText"><button id="executeJarvisCommand">Execute</button></form>
        <div id="jarvisCommandResult" class="hidden"></div>
        <div id="jarvisCommandError" class="error-box hidden"></div>
        <form id="jarvisQueryForm"><textarea id="jarvisQueryText"></textarea><button id="sendJarvisQuery">Send</button></form>
        <div id="jarvisQueryLoading" class="hidden"></div>
        <section id="jarvisQueryResult" class="hidden"></section>
        <dialog id="aiRuntimeDialog" class="task-dialog ai-runtime-dialog">
          <button id="closeAiRuntimeDialog" type="button">x</button>
          <div id="aiRuntimeModalError" class="error-box hidden"></div>
          <div id="aiRuntimeProviderOptions" role="radiogroup"></div>
          <div id="aiRuntimeModelOptions" role="radiogroup"></div>
          <section id="aiRuntimeEffortSection" class="hidden">
            <div id="aiRuntimeEffortOptions" role="radiogroup"></div>
          </section>
          <footer class="ai-runtime-modal-footer">
            <p class="ai-runtime-note">Active profile</p>
            <button id="cancelAiRuntimeDialog" type="button">Cancel</button>
            <button id="applyAiRuntimeDialog" type="button" disabled>Apply</button>
          </footer>
        </dialog>
      </main>
    </body>`, {
    url: 'http://127.0.0.1/fgaisox/operator/jarvis.html',
    pretendToBeVisual: true
  });
}

function installDialog(dialog: HTMLDialogElement) {
  const showModal = vi.fn(() => {
    dialog.setAttribute('open', '');
  });
  const close = vi.fn(() => {
    dialog.removeAttribute('open');
    dialog.dispatchEvent(new dialog.ownerDocument.defaultView!.Event('close'));
  });
  dialog.showModal = showModal;
  dialog.close = close;
  return { showModal, close };
}

function jarvisStatus() {
  return {
    status: 'UP',
    host: '127.0.0.1',
    port: 9999,
    model: { defaultModel: 'stale-configured-model' }
  };
}

function runtimeProviders() {
  return {
    providers: [
      {
        providerId: 'ollama',
        displayName: 'Ollama',
        status: 'READY',
        models: [
          { modelId: 'qwen2.5-coder:14b', displayName: 'Qwen 14B' },
          { modelId: 'another-model', displayName: 'Another Model' }
        ]
      },
      {
        providerId: 'codex',
        displayName: 'Codex',
        status: 'READY',
        version: '0.146.0',
        models: [
          {
            modelId: 'gpt-5.6-luna',
            displayName: 'GPT-5.6-Luna',
            description: 'Fast responses with compact reasoning.',
            modifiedAt: '2026-07-29T14:03:00Z',
            efforts: [
              { effortId: 'low', description: 'Fast responses.' },
              { effortId: 'high', description: 'Deeper reasoning.' }
            ]
          }
        ]
      },
      {
        providerId: 'local-degraded',
        displayName: 'Local Degraded',
        status: 'DEGRADED',
        models: [{ modelId: 'degraded-model', displayName: 'Degraded Model' }]
      }
    ]
  };
}

function activeProfile(overrides: Record<string, unknown> = {}) {
  return {
    revision: 3,
    llmProfile: {
      providerId: 'ollama',
      modelId: 'qwen2.5-coder:14b',
      effort: null
    },
    embeddingProfile: {
      providerId: 'ollama',
      modelId: 'embeddinggemma',
      status: 'READY',
      providerVersion: '0.32.5',
      embeddingDimension: 768,
      lastCheckedAt: '2026-08-01T00:00:00Z',
      diagnostic: null
    },
    usage: null,
    ...overrides
  };
}

function createPage(options: {
  status?: unknown;
  runtime?: unknown;
  profile?: unknown;
  get?: ReturnType<typeof vi.fn>;
  put?: ReturnType<typeof vi.fn>;
} = {}) {
  const dom = jarvisDom();
  const dialog = dom.window.document.getElementById('aiRuntimeDialog') as HTMLDialogElement;
  const dialogApi = installDialog(dialog);
  const http = {
    get: options.get || vi.fn((path: string) => {
      if (path === '/jarvis/status') {
        return Promise.resolve(options.status ?? jarvisStatus());
      }
      if (path === '/knowledge/ai-runtime') {
        return Promise.resolve(options.runtime ?? runtimeProviders());
      }
      if (path === '/knowledge/active-profile') {
        return Promise.resolve(options.profile ?? activeProfile());
      }
      return Promise.reject(new Error(`unexpected GET ${path}`));
    }),
    post: vi.fn(),
    put: options.put || vi.fn(() => Promise.resolve({
      revision: 4,
      llmProfile: {
        providerId: 'ollama',
        modelId: 'another-model',
        effort: null
      }
    }))
  };
  const page = new JarvisPage({ document: dom.window.document, http });
  return { dom, dialog, dialogApi, http, page };
}

async function flushAsync() {
  for (let index = 0; index < 10; index += 1) {
    await Promise.resolve();
  }
}

function text(dom: JSDOM) {
  return dom.window.document.body.textContent || '';
}

function option(dom: JSDOM, selector: string) {
  return dom.window.document.querySelector(selector) as HTMLButtonElement;
}

function getPaths(mock: ReturnType<typeof vi.fn>) {
  return mock.mock.calls.map((call) => String(call[0]));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

describe('Jarvis AI runtime modal', () => {
  it('renders Runtime panel from active profile and does not show stale configured model', async () => {
    const { dom, http, page } = createPage();

    page.mount();
    await flushAsync();

    expect(text(dom)).toContain('Active LLM');
    expect(text(dom)).toContain('qwen2.5-coder:14b');
    expect(text(dom)).toContain('ollama');
    expect(text(dom)).toContain('Active Embedding');
    expect(text(dom)).toContain('embeddinggemma');
    expect(text(dom)).toContain('Ollama · READY · 768 dimensions');
    expect(text(dom)).not.toContain('stale-configured-model');
    expect(text(dom)).not.toContain('Usage');
    expect(getPaths(http.get)).toEqual(['/jarvis/status', '/knowledge/active-profile']);
  });

  it('renders Active Embedding unavailable, disabled, and missing states safely', async () => {
    const unavailable = createPage({
      profile: activeProfile({
        embeddingProfile: {
          providerId: 'ollama',
          modelId: 'embeddinggemma',
          status: 'UNAVAILABLE',
          providerVersion: '0.32.5',
          embeddingDimension: null,
          lastCheckedAt: '2026-08-01T00:00:00Z',
          diagnostic: {
            code: 'SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE',
            message: 'Configured embedding model is unavailable.'
          }
        }
      })
    });
    unavailable.page.mount();
    await flushAsync();
    expect(unavailable.page.aiRuntimeView.activeProfile.embeddingProfile.embeddingDimension).toBeNull();
    expect(text(unavailable.dom)).toContain('Active Embedding');
    expect(text(unavailable.dom)).toContain('Ollama · UNAVAILABLE');
    expect(text(unavailable.dom)).toContain('Configured embedding model is unavailable.');
    expect(text(unavailable.dom)).not.toContain('0 dimensions');

    const disabled = createPage({
      profile: activeProfile({
        embeddingProfile: {
          providerId: 'ollama',
          modelId: 'embeddinggemma',
          status: 'DISABLED',
          providerVersion: null,
          embeddingDimension: null,
          lastCheckedAt: '2026-08-01T00:00:00Z',
          diagnostic: null
        }
      })
    });
    disabled.page.mount();
    await flushAsync();
    expect(text(disabled.dom)).toContain('Disabled');
    expect(text(disabled.dom)).toContain('semantic indexing');

    const missing = createPage({ profile: activeProfile({ embeddingProfile: undefined }) });
    missing.page.mount();
    await flushAsync();
    expect(text(missing.dom)).toContain('Active Embedding');
    expect(text(missing.dom)).toContain('semantic indexing');
  });

  it('general Refresh reloads status and active profile without runtime catalog discovery', async () => {
    const { dom, http, page } = createPage();

    page.mount();
    await flushAsync();
    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.Event('click'));
    await flushAsync();

    expect(getPaths(http.get).filter((path) => path === '/jarvis/status')).toHaveLength(2);
    expect(getPaths(http.get).filter((path) => path === '/knowledge/active-profile')).toHaveLength(2);
    expect(getPaths(http.get).filter((path) => path === '/knowledge/ai-runtime')).toHaveLength(0);
  });

  it('general Refresh updates Active Embedding from active profile only', async () => {
    let profile = activeProfile();
    const get = vi.fn((path: string) => {
      if (path === '/jarvis/status') return Promise.resolve(jarvisStatus());
      if (path === '/knowledge/active-profile') return Promise.resolve(profile);
      if (path === '/knowledge/ai-runtime') return Promise.resolve(runtimeProviders());
      return Promise.reject(new Error(`unexpected GET ${path}`));
    });
    const { dom, page } = createPage({ get });

    page.mount();
    await flushAsync();
    expect(text(dom)).toContain('Ollama · READY · 768 dimensions');

    profile = activeProfile({
      embeddingProfile: {
        providerId: 'ollama',
        modelId: 'embeddinggemma',
        status: 'UNAVAILABLE',
        providerVersion: '0.32.5',
        embeddingDimension: null,
        lastCheckedAt: '2026-08-01T00:00:00Z',
        diagnostic: { code: 'SEMANTIC_PROVIDER_UNAVAILABLE', message: 'Configured embedding provider is unavailable.' }
      }
    });
    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.Event('click'));
    await flushAsync();

    expect(text(dom)).toContain('Ollama · UNAVAILABLE');
    expect(text(dom)).toContain('Configured embedding provider is unavailable.');
    expect(getPaths(get).filter((path) => path === '/knowledge/active-profile')).toHaveLength(2);
    expect(getPaths(get).filter((path) => path === '/knowledge/ai-runtime')).toHaveLength(0);
  });

  it('opens with active provider and model preselected and unchanged Apply disabled', async () => {
    const { dom, dialog, dialogApi, page } = createPage();
    page.mount();
    await flushAsync();

    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    expect(dialogApi.showModal).toHaveBeenCalledTimes(1);
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(option(dom, '[data-provider-id="ollama"]').getAttribute('aria-checked')).toBe('true');
    expect(option(dom, '[data-model-id="qwen2.5-coder:14b"]').getAttribute('aria-checked')).toBe('true');
    expect(dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(true);
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('No changes to apply');
  });

  it('enables Apply for valid changed draft and sends exact active-profile PUT body', async () => {
    const update = deferred<unknown>();
    const put = vi.fn(() => update.promise);
    const { dom, dialog, http, page } = createPage({ put });
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    option(dom, '[data-model-id="another-model"]').click();
    const apply = dom.window.document.getElementById('applyAiRuntimeDialog') as HTMLButtonElement;
    expect(apply.hasAttribute('disabled')).toBe(false);
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('Ready to apply');
    apply.click();

    expect(http.put).toHaveBeenCalledWith('/knowledge/active-profile/llm-profile', {
      expectedRevision: 3,
      providerId: 'ollama',
      modelId: 'another-model',
      effort: null
    });
    expect(http.put).toHaveBeenCalledTimes(1);
    expect(apply.hasAttribute('disabled')).toBe(true);
    expect(apply.textContent).toBe('Applying...');
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('Applying active profile...');

    apply.click();
    expect(http.put).toHaveBeenCalledTimes(1);

    update.resolve({
      revision: 4,
      llmProfile: {
        providerId: 'ollama',
        modelId: 'another-model',
        effort: null
      }
    });
    await flushAsync();

    expect(dialog.hasAttribute('open')).toBe(false);
    expect(getPaths(http.get).filter((path) => path === '/knowledge/active-profile')).toHaveLength(2);
    expect(getPaths(http.get).filter((path) => path === '/knowledge/ai-runtime')).toHaveLength(1);
  });

  it('keeps modal open on failed PUT and does not pretend activation succeeded', async () => {
    const error = Object.assign(new Error('not executable'), {
      status: 400,
      code: 'ACTIVE_LLM_PROVIDER_NOT_EXECUTABLE'
    });
    const { dom, dialog, http, page } = createPage({ put: vi.fn(() => Promise.reject(error)) });
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    option(dom, '[data-provider-id="codex"]').click();
    option(dom, '[data-model-id="gpt-5.6-luna"]').click();
    option(dom, '[data-effort-id="high"]').click();
    dom.window.document.getElementById('applyAiRuntimeDialog')?.click();
    await flushAsync();

    expect(dialog.hasAttribute('open')).toBe(true);
    expect(http.put).toHaveBeenCalledWith('/knowledge/active-profile/llm-profile', {
      expectedRevision: 3,
      providerId: 'codex',
      modelId: 'gpt-5.6-luna',
      effort: { effortId: 'high' }
    });
    expect(text(dom)).toContain('Active profile update failed');
    expect(text(dom)).not.toContain('ACTIVE_LLM_PROVIDER_NOT_EXECUTABLE');
    expect(text(dom)).toContain('qwen2.5-coder:14b');
  });

  it('reloads active profile and resets draft on revision conflict', async () => {
    const conflict = Object.assign(new Error('conflict'), {
      status: 409,
      code: 'ACTIVE_PROFILE_REVISION_CONFLICT'
    });
    let profile = activeProfile();
    const get = vi.fn((path: string) => {
      if (path === '/jarvis/status') return Promise.resolve(jarvisStatus());
      if (path === '/knowledge/ai-runtime') return Promise.resolve(runtimeProviders());
      if (path === '/knowledge/active-profile') return Promise.resolve(profile);
      return Promise.reject(new Error(`unexpected GET ${path}`));
    });
    const put = vi.fn(() => {
      profile = activeProfile({
        revision: 4,
        llmProfile: { providerId: 'ollama', modelId: 'another-model', effort: null },
        usage: null
      });
      return Promise.reject(conflict);
    });
    const { dom, page } = createPage({ get, put });
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    option(dom, '[data-model-id="another-model"]').click();
    dom.window.document.getElementById('applyAiRuntimeDialog')?.click();
    await flushAsync();

    expect(getPaths(get).filter((path) => path === '/knowledge/active-profile')).toHaveLength(2);
    expect(option(dom, '[data-model-id="another-model"]').getAttribute('aria-checked')).toBe('true');
    expect(text(dom)).toContain('Active profile changed');
  });

  it('renders one or two usage bars only from active-profile usage windows', async () => {
    const { dom, page } = createPage({
      profile: activeProfile({
        usage: {
          windows: [
            { kind: 'PRIMARY', usedPercent: 34, windowDurationMinutes: 300, resetAt: '2026-07-30T18:30:00Z' },
            { kind: 'SECONDARY', usedPercent: 61, windowDurationMinutes: 10080, resetAt: '2026-08-04T09:00:00Z' }
          ]
        }
      })
    });
    page.mount();
    await flushAsync();

    expect(text(dom)).toContain('Usage');
    expect(text(dom)).toContain('5-hour usage');
    expect(text(dom)).toContain('34% used · 66% remaining');
    expect(text(dom)).toContain('Weekly usage');
    expect(dom.window.document.querySelectorAll('.ai-runtime-progress')).toHaveLength(2);

    const one = createPage({
      profile: activeProfile({
        usage: {
          windows: [{ kind: 'PRIMARY', usedPercent: 10, windowDurationMinutes: 1440, resetAt: '2026-07-30T18:30:00Z' }]
        }
      })
    });
    one.page.mount();
    await flushAsync();
    expect(one.dom.window.document.querySelectorAll('.ai-runtime-progress')).toHaveLength(1);
  });

  it('progressively selects provider, model, and effort without provider-specific branches', async () => {
    const { dom, page } = createPage();
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    option(dom, '[data-provider-id="codex"]').click();
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('GPT-5.6-Luna');
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).not.toContain('embeddinggemma');
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('Select a provider and model');
    option(dom, '[data-model-id="gpt-5.6-luna"]').click();
    expect(dom.window.document.getElementById('aiRuntimeEffortSection')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('Select a reasoning effort');
    option(dom, '[data-effort-id="high"]').click();
    expect(option(dom, '[data-effort-id="high"]').getAttribute('aria-checked')).toBe('true');
    expect(dom.window.document.querySelector('.ai-runtime-note')?.textContent).toBe('Ready to apply');

    const source = await readFile(resolve(testDir, '../src/operator/ai-runtime-view.js'), 'utf8');
    expect(source).not.toMatch(/providerId\s*={2,3}\s*['"`](ollama|codex|claude)['"`]/);
  });

  it('keeps AI runtime dialog out of the stretching task-dialog inset layout', async () => {
    const source = await readFile(resolve(testDir, '../src/operator/operator-ui.css'), 'utf8');
    const match = source.match(/\.ai-runtime-dialog\.open\s*\{(?<body>[^}]+)\}/);

    expect(match?.groups?.body).toContain('top: 50%');
    expect(match?.groups?.body).toContain('left: 50%');
    expect(match?.groups?.body).toContain('height: auto');
    expect(match?.groups?.body).not.toContain('inset: 22px');
  });

  it('isolates status and runtime failures and preserves last good providers', async () => {
    const statusFailure = Object.assign(new Error('status down'), { code: 'STATUS_DOWN' });
    const first = createPage({
      get: vi.fn((path: string) => {
        if (path === '/jarvis/status') return Promise.reject(statusFailure);
        if (path === '/knowledge/ai-runtime') return Promise.resolve(runtimeProviders());
        if (path === '/knowledge/active-profile') return Promise.resolve(activeProfile());
        return Promise.reject(new Error(`unexpected GET ${path}`));
      })
    });
    first.page.mount();
    await flushAsync();
    expect(text(first.dom)).toContain('qwen2.5-coder:14b');
    expect(first.dom.window.document.getElementById('jarvisStatusError')?.classList.contains('hidden')).toBe(false);

    let runtimeFails = false;
    const second = createPage({
      get: vi.fn((path: string) => {
        if (path === '/jarvis/status') return Promise.resolve(jarvisStatus());
        if (runtimeFails && path === '/knowledge/active-profile') {
          return Promise.reject(Object.assign(new Error('runtime down'), { code: 'RUNTIME_DOWN' }));
        }
        if (path === '/knowledge/ai-runtime') return Promise.resolve(runtimeProviders());
        if (path === '/knowledge/active-profile') return Promise.resolve(activeProfile());
        return Promise.reject(new Error(`unexpected GET ${path}`));
      })
    });
    second.page.mount();
    await flushAsync();
    expect(text(second.dom)).toContain('qwen2.5-coder:14b');
    runtimeFails = true;
    second.dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new second.dom.window.Event('click'));
    await flushAsync();
    expect(text(second.dom)).toContain('qwen2.5-coder:14b');
    expect(second.dom.window.document.getElementById('aiRuntimeError')?.classList.contains('hidden')).toBe(false);
  });

  it('loads a fresh runtime catalog on every Edit and renders only the latest response', async () => {
    const catalogs = [
      {
        providers: [{
          providerId: 'ollama',
          displayName: 'Ollama',
          status: 'READY',
          models: [{ modelId: 'old-model', displayName: 'Old Model' }]
        }]
      },
      {
        providers: [{
          providerId: 'ollama',
          displayName: 'Ollama',
          status: 'READY',
          models: [{ modelId: 'new-model', displayName: 'New Model' }]
        }]
      }
    ];
    const get = vi.fn((path: string) => {
      if (path === '/jarvis/status') return Promise.resolve(jarvisStatus());
      if (path === '/knowledge/active-profile') {
        return Promise.resolve(activeProfile({ llmProfile: { providerId: 'ollama', modelId: 'old-model', effort: null } }));
      }
      if (path === '/knowledge/ai-runtime') return Promise.resolve(catalogs.shift());
      return Promise.reject(new Error(`unexpected GET ${path}`));
    });
    const { dom, dialog, page } = createPage({ get });

    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();
    expect(text(dom)).toContain('Old Model');
    dialog.close();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    expect(getPaths(get).filter((path) => path === '/knowledge/ai-runtime')).toHaveLength(2);
    expect(text(dom)).toContain('New Model');
    expect(text(dom)).not.toContain('Old Model');
  });

  it('disables Apply while discovery is pending and cannot apply previous catalog after discovery failure', async () => {
    const pending = deferred<unknown>();
    let runtimeCalls = 0;
    const get = vi.fn((path: string) => {
      if (path === '/jarvis/status') return Promise.resolve(jarvisStatus());
      if (path === '/knowledge/active-profile') return Promise.resolve(activeProfile());
      if (path === '/knowledge/ai-runtime') {
        runtimeCalls += 1;
        if (runtimeCalls === 1) return Promise.resolve(runtimeProviders());
        return pending.promise;
      }
      return Promise.reject(new Error(`unexpected GET ${path}`));
    });
    const { dom, dialog, http, page } = createPage({ get });

    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();
    option(dom, '[data-model-id="another-model"]').click();
    expect(dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(false);
    dialog.close();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    expect(dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(true);
    expect(dom.window.document.querySelector('[data-model-id="another-model"]')).toBeNull();

    pending.reject(Object.assign(new Error('catalog down'), { status: 503 }));
    await flushAsync();
    dom.window.document.getElementById('applyAiRuntimeDialog')?.click();

    expect(http.put).not.toHaveBeenCalled();
    expect(text(dom)).toContain('AI runtime catalog failed');
  });

  it('keeps non-ready providers visible but disabled', async () => {
    const { dom, page } = createPage();

    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    const degraded = option(dom, '[data-provider-id="local-degraded"]');
    expect(degraded.textContent).toContain('Local Degraded');
    expect(degraded.hasAttribute('disabled')).toBe(true);
  });
});
