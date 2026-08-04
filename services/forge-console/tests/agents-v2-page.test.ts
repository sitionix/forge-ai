import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { AgentsV2Page } from '../src/operator/agents-v2-page.js';
import { createAgentsV2Api } from '../src/operator/agents-v2-api.js';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';

function agentsV2Dom() {
  return new JSDOM(`<!doctype html>
    <body data-page="agents-v2">
      <button id="agentsV2CreateProject" type="button"></button>
      <div id="agentsV2ProjectsError" class="hidden"></div>
      <div id="agentsV2ProjectsList"></div>
      <p id="agentsV2AgentsSubtitle"></p>
      <button id="agentsV2CreateAgent" type="button"></button>
      <div id="agentsV2AgentsError" class="hidden"></div>
      <div id="agentsV2AgentsList"></div>
      <dialog id="agentsV2ProjectDialog">
        <form id="agentsV2ProjectForm">
          <div id="agentsV2ProjectModalError" class="hidden"></div>
          <input id="agentsV2ProjectName">
          <button id="agentsV2ProjectCancel" type="button"></button>
          <button id="agentsV2ProjectSave" type="submit"></button>
        </form>
      </dialog>
      <dialog id="agentsV2AgentDialog">
        <form id="agentsV2AgentForm">
          <h2 id="agentsV2AgentModalTitle"></h2>
          <div id="agentsV2AgentModalError" class="hidden"></div>
          <input id="agentsV2AgentName">
          <textarea id="agentsV2AgentInstructions"></textarea>
          <textarea id="agentsV2AgentOutputJson"></textarea>
          <div id="agentsV2AgentJsonError" class="hidden"></div>
          <div id="agentsV2AgentDependencies"></div>
          <button id="agentsV2AgentCancel" type="button"></button>
          <button id="agentsV2AgentSave" type="submit"></button>
        </form>
      </dialog>
    </body>`, {
    url: 'http://127.0.0.1/operator/agents-v2.html',
    pretendToBeVisual: true
  });
}

async function flushAsync() {
  for (let index = 0; index < 12; index += 1) {
    await Promise.resolve();
  }
}

function project(id = '11111111-1111-4111-8111-111111111111', name = 'Sitionix') {
  return { id, name, createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z' };
}

function agent(id: string, name: string, dependsOn: Array<{ id: string; name: string }> = [], projectId = project().id) {
  return { id, projectId, name, dependsOn, createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z' };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function api(overrides = {}) {
  return {
    listProjects: vi.fn(() => Promise.resolve([project()])),
    createProject: vi.fn(() => Promise.resolve(project('22222222-2222-4222-8222-222222222222', 'Forge AI'))),
    listProjectAgents: vi.fn(() => Promise.resolve([
      agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend Implementer')
    ])),
    createAgent: vi.fn(() => Promise.resolve(agent('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'Analyzer'))),
    getAgent: vi.fn(() => Promise.resolve({
      ...agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend Implementer', [{ id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Architect' }]),
      instructions: 'Implement backend changes.\nPreserve formatting.',
      outputSchema: { type: 'object', properties: { summary: { type: 'string' } } }
    })),
    updateAgent: vi.fn(() => Promise.resolve(agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend Implementer'))),
    ...overrides
  };
}

describe('Agents V2 page', () => {
  it('sidebar contains both Agents and Agents V2', () => {
    const dom = agentsV2Dom();
    bootstrapOperatorConsole({ document: dom.window.document, window: dom.window, api: api(), http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } });
    expect(dom.window.document.body.textContent).toContain('Agents');
    expect(dom.window.document.body.textContent).toContain('Agents V2');
    expect(consoleSourceText()).toContain('Agents V2');
  });

  it('initializes, loads projects, and selecting a project loads agents', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2ProjectsList')?.textContent).toContain('Sitionix');
    dom.window.document.querySelector<HTMLElement>('[data-project-id]')?.click();
    await flushAsync();

    expect(fakeApi.listProjectAgents).toHaveBeenCalledWith(project().id);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect((dom.window.document.getElementById('agentsV2CreateAgent') as HTMLButtonElement).disabled).toBe(false);
  });

  it('creates a project, selects it, and reloads agents', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();

    dom.window.document.getElementById('agentsV2CreateProject')?.dispatchEvent(new dom.window.Event('click'));
    (dom.window.document.getElementById('agentsV2ProjectName') as HTMLInputElement).value = 'Forge AI';
    dom.window.document.getElementById('agentsV2ProjectForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProject).toHaveBeenCalledWith({ name: 'Forge AI' });
    expect(fakeApi.listProjectAgents).toHaveBeenCalledWith('22222222-2222-4222-8222-222222222222');
  });

  it('creates an agent with selected dependencies', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);

    await page.openAgentModal();
    (dom.window.document.getElementById('agentsV2AgentName') as HTMLInputElement).value = 'Analyzer';
    (dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value = 'Analyze changes.';
    const dependency = dom.window.document.querySelector<HTMLInputElement>('#agentsV2AgentDependencies input');
    if (dependency) {
      dependency.checked = true;
    }
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createAgent).toHaveBeenCalledWith(project().id, expect.objectContaining({
      name: 'Analyzer',
      dependsOnAgentIds: ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']
    }));
  });

  it('edits an agent with preselected dependencies and refreshes the list', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);

    await page.openAgentModal('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    expect((dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value).toContain('Preserve formatting.');
    const checked = dom.window.document.querySelector<HTMLInputElement>('#agentsV2AgentDependencies input:checked');
    expect(checked?.value).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.updateAgent).toHaveBeenCalled();
    expect(fakeApi.listProjectAgents).toHaveBeenCalledTimes(2);
  });

  it('blocks invalid and non-object output JSON before request submission', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openAgentModal();
    (dom.window.document.getElementById('agentsV2AgentName') as HTMLInputElement).value = 'Analyzer';
    (dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value = 'Analyze changes.';

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '{';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toContain('not valid JSON');

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '[]';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toContain('JSON object');
  });

  it('renders backend validation errors in the modal', async () => {
    const dom = agentsV2Dom();
    const fakeApi = api({ createAgent: vi.fn(() => Promise.reject(new Error('Dependency graph contains a cycle.'))) });
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openAgentModal();
    (dom.window.document.getElementById('agentsV2AgentName') as HTMLInputElement).value = 'Analyzer';
    (dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value = 'Analyze changes.';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2AgentModalError')?.textContent).toContain('cycle');
  });

  it('switching projects clears the previous agent list immediately and disables create while loading', async () => {
    const dom = agentsV2Dom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const secondLoad = deferred<ReturnType<typeof agent>[]>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => {
        if (projectId === projectOne.id) {
          return Promise.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect')]);
        }
        return secondLoad.promise;
      })
    });
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(projectOne.id);

    const switching = page.selectProject(projectTwo.id);
    await flushAsync();

    expect(page.state.agents).toEqual([]);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Loading agents');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).not.toContain('Architect');
    expect((dom.window.document.getElementById('agentsV2CreateAgent') as HTMLButtonElement).disabled).toBe(true);

    secondLoad.resolve([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend', [], projectTwo.id)]);
    await switching;
  });

  it('failed project-agent request leaves no stale dependency options and create remains disabled', async () => {
    const dom = agentsV2Dom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => {
        if (projectId === projectOne.id) {
          return Promise.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect')]);
        }
        return Promise.reject(new Error('Agents failed to load.'));
      })
    });
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(projectOne.id);
    await page.selectProject(projectTwo.id);

    expect(page.state.agents).toEqual([]);
    expect(page.state.agentsProjectId).toBeNull();
    expect((dom.window.document.getElementById('agentsV2CreateAgent') as HTMLButtonElement).disabled).toBe(true);
    expect(dom.window.document.getElementById('agentsV2AgentsError')?.textContent).toContain('Agents failed');

    await page.openAgentModal();
    expect(dom.window.document.getElementById('agentsV2AgentDependencies')?.textContent).not.toContain('Architect');
  });

  it('older project-agent response cannot overwrite a newer project selection', async () => {
    const dom = agentsV2Dom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const firstLoad = deferred<ReturnType<typeof agent>[]>();
    const secondLoad = deferred<ReturnType<typeof agent>[]>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => projectId === projectOne.id ? firstLoad.promise : secondLoad.promise)
    });
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();

    const firstSelection = page.selectProject(projectOne.id);
    const secondSelection = page.selectProject(projectTwo.id);
    secondLoad.resolve([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend', [], projectTwo.id)]);
    await secondSelection;
    firstLoad.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect', [], projectOne.id)]);
    await firstSelection;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.agentsProjectId).toBe(projectTwo.id);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Backend');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).not.toContain('Architect');
  });

  it('agent modal only displays agents from the currently loaded selected project', async () => {
    const dom = agentsV2Dom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => {
        if (projectId === projectOne.id) {
          return Promise.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect', [], projectOne.id)]);
        }
        return Promise.resolve([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend', [], projectTwo.id)]);
      })
    });
    const page = new AgentsV2Page({ document: dom.window.document, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(projectOne.id);
    await page.selectProject(projectTwo.id);

    await page.openAgentModal();

    expect(dom.window.document.getElementById('agentsV2AgentDependencies')?.textContent).toContain('Backend');
    expect(dom.window.document.getElementById('agentsV2AgentDependencies')?.textContent).not.toContain('Architect');
  });

  it('uses Nexus infrastructure routes only', () => {
    const http = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
    const client = createAgentsV2Api(http);
    client.listProjects();
    client.createProject({ name: 'Sitionix' });
    client.listProjectAgents(project().id);
    client.createAgent(project().id, { name: 'Agent', instructions: 'Do work.', outputSchema: {}, dependsOnAgentIds: [] });
    client.getAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    client.updateAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', { name: 'Agent', instructions: 'Do work.', outputSchema: {}, dependsOnAgentIds: [] });

    const calls = [...http.get.mock.calls, ...http.post.mock.calls, ...http.put.mock.calls].map(([path]) => path);
    expect(calls.every((path) => path.startsWith('/agents'))).toBe(true);
    expect(consoleSourceText()).not.toContain('7091');
    expect(consoleSourceText()).not.toContain('FORGE_AGENT');
  });
});

function consoleSourceText() {
  const root = join(process.cwd(), 'src', 'operator');
  return walk(root)
    .filter((file) => file.endsWith('.js') || file.endsWith('.html'))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');
}

function walk(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      return walk(path);
    }
    return [path];
  });
}
