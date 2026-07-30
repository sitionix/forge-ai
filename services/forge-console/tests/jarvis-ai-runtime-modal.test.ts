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
        <header class="hero">
          <h1>Jarvis</h1>
          <button id="refreshJarvis" type="button" class="button">Refresh</button>
        </header>
        <section class="panel jarvis-status-panel">
          <div class="panel-head">
            <div>
              <h2>Runtime</h2>
              <p>Local Jarvis service and available AI runtimes.</p>
            </div>
            <div class="runtime-panel-actions">
              <button id="editAiRuntime" type="button" class="button ghost dark small">Edit</button>
              <span id="jarvisUpdated" class="muted">loading</span>
            </div>
          </div>
          <div id="jarvisStatusCards" class="jarvis-status-grid"></div>
          <div id="jarvisStatusError" class="error-box hidden"></div>
          <div id="aiRuntimeError" class="error-box hidden"></div>
        </section>
        <div class="jarvis-grid single">
          <section class="panel">
            <div class="panel-head compact"><h2>Command Test</h2></div>
            <form id="jarvisCommandForm">
              <input id="jarvisCommandText" type="text">
              <button id="executeJarvisCommand" type="submit">Execute</button>
            </form>
            <div id="jarvisCommandResult" class="hidden"></div>
            <div id="jarvisCommandError" class="error-box hidden"></div>
          </section>
        </div>
        <section class="panel jarvis-query-panel">
          <div class="panel-head compact"><h2>Graph Knowledge Query</h2></div>
          <form id="jarvisQueryForm">
            <textarea id="jarvisQueryText"></textarea>
            <button id="sendJarvisQuery" type="submit">Send</button>
          </form>
          <div id="jarvisQueryLoading" class="hidden"></div>
          <section id="jarvisQueryResult" class="hidden"></section>
        </section>
        <dialog id="aiRuntimeDialog" class="task-dialog ai-runtime-dialog">
          <div class="dialog-head">
            <div>
              <h2>AI Runtime</h2>
              <p>Choose a provider, model and available model options.</p>
            </div>
            <button id="closeAiRuntimeDialog" type="button" class="button ghost dark small" aria-label="Close AI Runtime dialog">×</button>
          </div>
          <div class="ai-runtime-modal-body">
            <div id="aiRuntimeModalError" class="error-box hidden"></div>
            <div id="aiRuntimePicker" class="ai-runtime-picker">
              <section class="ai-runtime-section" aria-labelledby="aiRuntimeProviderHeading">
                <h3 id="aiRuntimeProviderHeading">Provider</h3>
                <div id="aiRuntimeProviderOptions" class="ai-runtime-options" role="radiogroup"></div>
              </section>
              <section class="ai-runtime-section" aria-labelledby="aiRuntimeModelHeading">
                <h3 id="aiRuntimeModelHeading">Model</h3>
                <div id="aiRuntimeModelOptions" class="ai-runtime-options" role="radiogroup"></div>
              </section>
              <section id="aiRuntimeEffortSection" class="ai-runtime-section hidden" aria-labelledby="aiRuntimeEffortHeading">
                <h3 id="aiRuntimeEffortHeading">Reasoning Effort</h3>
                <div id="aiRuntimeEffortOptions" class="ai-runtime-options" role="radiogroup"></div>
              </section>
            </div>
          </div>
          <footer class="ai-runtime-modal-footer">
            <p class="ai-runtime-note">Applying runtime changes will be enabled with runtime profiles.</p>
            <div class="form-actions">
              <button id="cancelAiRuntimeDialog" type="button" class="button ghost dark">Cancel</button>
              <button id="applyAiRuntimeDialog" type="button" class="button" disabled>Apply</button>
            </div>
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
    ollama: { status: 'UNKNOWN', baseUrl: 'http://old-status' },
    model: { defaultModel: 'qwen2.5-coder:14b' },
    actions: { count: 2 }
  };
}

function runtimeProviders() {
  return {
    providers: [
      {
        providerId: 'ollama',
        displayName: 'Ollama',
        status: 'UNAVAILABLE',
        models: [{ modelId: 'qwen2.5-coder:14b', displayName: 'Qwen local' }]
      },
      {
        providerId: 'codex',
        displayName: 'Codex',
        status: 'READY',
        version: '0.146.0',
        models: [
          {
            modelId: 'gpt-5.6-sol',
            displayName: 'GPT-5.6-Sol',
            description: 'Fast responses with compact reasoning.',
            modifiedAt: '2026-07-29T14:03:00Z',
            efforts: [
              { effortId: 'low', description: 'Fast responses.' },
              { effortId: 'high', description: 'Deeper reasoning.' }
            ]
          },
          {
            modelId: 'gpt-5.6-luna',
            displayName: 'GPT-5.6-Luna',
            description: 'General runtime model.'
          }
        ]
      },
      {
        providerId: 'claude',
        displayName: 'Claude',
        status: 'READY',
        models: [{ modelId: 'claude-example', displayName: 'Claude Example' }]
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

function createPage(options: { status?: unknown; runtime?: unknown; get?: ReturnType<typeof vi.fn> } = {}) {
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
      return Promise.reject(new Error(`unexpected GET ${path}`));
    }),
    post: vi.fn()
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

describe('Jarvis AI runtime modal', () => {
  it('renders Runtime panel from AI runtime providers and omits legacy actions UI', async () => {
    const { dom, http, page } = createPage();

    page.mount();
    await flushAsync();

    const panel = dom.window.document.querySelector('.jarvis-status-panel');
    expect(panel?.querySelector('#editAiRuntime')).not.toBeNull();
    expect(panel?.querySelector('#editAiRuntime')?.className).toContain('button ghost dark small');
    expect(text(dom)).toContain('Ollama');
    expect(text(dom)).toContain('UNAVAILABLE');
    expect(text(dom)).toContain('Codex');
    expect(text(dom)).toContain('READY');
    expect(text(dom)).toContain('0.146.0');
    expect(text(dom)).toContain('Claude');
    expect(text(dom)).toContain('qwen2.5-coder:14b');
    expect(text(dom)).not.toContain('UNKNOWN');
    expect(text(dom)).not.toContain('Actions');
    expect(text(dom)).not.toContain('Allowlisted Actions');
    expect(dom.window.document.getElementById('jarvisActions')).toBeNull();
    expect(dom.window.document.getElementById('jarvisActionsError')).toBeNull();
    expect(dom.window.document.querySelector('.jarvis-grid.single')).not.toBeNull();
    expect(getPaths(http.get)).toEqual(['/jarvis/status', '/knowledge/ai-runtime']);

    dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new dom.window.Event('click'));
    await flushAsync();
    expect(getPaths(http.get)).toEqual([
      '/jarvis/status',
      '/knowledge/ai-runtime',
      '/jarvis/status',
      '/knowledge/ai-runtime'
    ]);
    expect(getPaths(http.get).some((path) => path === '/jarvis/actions')).toBe(false);
  });

  it('opens with showModal over the same page and does not navigate', async () => {
    const { dom, dialog, dialogApi, http, page } = createPage();
    page.mount();
    await flushAsync();
    const beforeUrl = dom.window.location.href;

    dom.window.document.getElementById('editAiRuntime')?.click();
    await flushAsync();

    expect(dialogApi.showModal).toHaveBeenCalledTimes(1);
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.classList.contains('open')).toBe(true);
    expect(dom.window.location.href).toBe(beforeUrl);
    expect(dom.window.document.querySelector('h1')?.textContent).toBe('Jarvis');
    expect(dialog.contains(dom.window.document.activeElement)).toBe(true);
    expect(getPaths(http.get).filter((path) => path === '/knowledge/ai-runtime')).toHaveLength(1);
  });

  it('renders provider options with READY enabled and non-ready providers disabled', async () => {
    const { dom, page } = createPage();
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();

    const ollama = option(dom, '[data-provider-id="ollama"]');
    const codex = option(dom, '[data-provider-id="codex"]');
    const claude = option(dom, '[data-provider-id="claude"]');
    const degraded = option(dom, '[data-provider-id="local-degraded"]');
    expect(ollama.textContent).toContain('Ollama');
    expect(ollama.disabled).toBe(true);
    expect(codex.disabled).toBe(false);
    expect(codex.textContent).toContain('READY · 0.146.0');
    expect(claude.disabled).toBe(false);
    expect(claude.textContent).toContain('Claude');
    expect(claude.textContent).not.toContain('·');
    expect(degraded.disabled).toBe(true);
  });

  it('progressively selects provider, model, and optional efforts without provider-specific branches', async () => {
    const { dom, http, page } = createPage();
    page.mount();
    await flushAsync();
    dom.window.document.getElementById('editAiRuntime')?.click();

    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('Select a provider');
    option(dom, '[data-provider-id="ollama"]').dispatchEvent(new dom.window.Event('click', { bubbles: true }));
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('Select a provider');

    option(dom, '[data-provider-id="codex"]').click();
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('GPT-5.6-Sol');
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('Fast responses with compact reasoning.');
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('2026-07-29T14:03:00Z');
    expect(dom.window.document.getElementById('aiRuntimeEffortSection')?.classList.contains('hidden')).toBe(true);

    option(dom, '[data-model-id="gpt-5.6-sol"]').click();
    expect(dom.window.document.getElementById('aiRuntimeEffortSection')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('aiRuntimeEffortOptions')?.textContent).toContain('low');
    expect(dom.window.document.getElementById('aiRuntimeEffortOptions')?.textContent).toContain('Fast responses.');
    option(dom, '[data-effort-id="high"]').click();
    expect(option(dom, '[data-effort-id="high"]').getAttribute('aria-checked')).toBe('true');

    option(dom, '[data-model-id="gpt-5.6-luna"]').click();
    expect(dom.window.document.getElementById('aiRuntimeEffortSection')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(true);
    expect(http.post).not.toHaveBeenCalled();

    const source = await readFile(resolve(testDir, '../src/operator/ai-runtime-view.js'), 'utf8');
    expect(source).not.toMatch(/providerId\s*={2,3}\s*['"`](ollama|codex|claude)['"`]/);
  });

  it('keeps Apply disabled and resets draft on close, cancel, and Escape', async () => {
    const { dom, dialog, page } = createPage();
    page.mount();
    await flushAsync();
    const editButton = dom.window.document.getElementById('editAiRuntime') as HTMLButtonElement;

    editButton.click();
    option(dom, '[data-provider-id="codex"]').click();
    option(dom, '[data-model-id="gpt-5.6-sol"]').click();
    expect(dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(true);
    dom.window.document.getElementById('closeAiRuntimeDialog')?.click();
    expect(dom.window.document.activeElement).toBe(editButton);

    editButton.click();
    expect(dom.window.document.getElementById('aiRuntimeModelOptions')?.textContent).toContain('Select a provider');
    option(dom, '[data-provider-id="codex"]').click();
    dom.window.document.getElementById('cancelAiRuntimeDialog')?.click();
    expect(dom.window.document.activeElement).toBe(editButton);

    editButton.click();
    option(dom, '[data-provider-id="codex"]').click();
    dialog.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(dom.window.document.activeElement).toBe(editButton);
  });

  it('isolates status and runtime failures and preserves last good providers', async () => {
    const statusFailure = Object.assign(new Error('status down'), { code: 'STATUS_DOWN' });
    const first = createPage({
      get: vi.fn((path: string) => {
        if (path === '/jarvis/status') {
          return Promise.reject(statusFailure);
        }
        return Promise.resolve(runtimeProviders());
      })
    });
    first.page.mount();
    await flushAsync();
    expect(text(first.dom)).toContain('Codex');
    expect(first.dom.window.document.getElementById('jarvisStatusError')?.classList.contains('hidden')).toBe(false);

    let runtimeFails = false;
    const second = createPage({
      get: vi.fn((path: string) => {
        if (path === '/jarvis/status') {
          return Promise.resolve(jarvisStatus());
        }
        if (runtimeFails) {
          return Promise.reject(Object.assign(new Error('runtime down'), { code: 'RUNTIME_DOWN' }));
        }
        return Promise.resolve(runtimeProviders());
      })
    });
    second.page.mount();
    await flushAsync();
    expect(text(second.dom)).toContain('Codex');
    runtimeFails = true;
    second.dom.window.document.getElementById('refreshJarvis')?.dispatchEvent(new second.dom.window.Event('click'));
    await flushAsync();
    expect(text(second.dom)).toContain('Codex');
    expect(text(second.dom)).not.toContain('UNKNOWN');
    expect(second.dom.window.document.getElementById('aiRuntimeError')?.classList.contains('hidden')).toBe(false);

    const empty = createPage({ runtime: { providers: [] } });
    empty.page.mount();
    await flushAsync();
    empty.dom.window.document.getElementById('editAiRuntime')?.click();
    expect(text(empty.dom)).toContain('No AI runtime providers reported.');
    expect(empty.dom.window.document.getElementById('applyAiRuntimeDialog')?.hasAttribute('disabled')).toBe(true);
  });
});
