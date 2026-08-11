import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { AgentProjectsPage } from '../src/operator/agent-projects-page.js';
import { createAgentProjectsApi } from '../src/operator/agent-projects-api.js';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';
import { effortTone } from '../src/operator/project-workspace.js';

function agentProjectsDom() {
  return new JSDOM(readFileSync(join(process.cwd(), 'src', 'operator', 'agent-projects.html'), 'utf8'), {
    url: 'http://127.0.0.1/operator/agent-projects.html',
    pretendToBeVisual: true
  });
}

async function flushAsync() {
  for (let index = 0; index < 14; index += 1) {
    await Promise.resolve();
  }
}

function project(id = '11111111-1111-4111-8111-111111111111', name = 'Sitionix') {
  return { id, name, createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z' };
}

function agent(id: string, name: string, projectId = project().id) {
  return {
    id,
    projectId,
    name,
    instructions: `${name} instructions`,
    outputSchema: { type: 'object' },
    model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'medium' },
    createdAt: '2026-08-04T00:00:00Z',
    updatedAt: '2026-08-04T00:00:00Z'
  };
}

function runtime() {
  return {
    providers: [
      {
        providerId: 'codex',
        displayName: 'Codex',
        status: 'READY',
        version: 'codex 1.0.0',
        models: [
          {
            modelId: 'discovered-model',
            displayName: 'Discovered Model',
            description: 'Live model',
            efforts: [
              { effortId: 'medium', description: 'Medium' }
            ]
          }
        ]
      }
    ]
  };
}

function unavailableCodexRuntime(status = 'UNAVAILABLE') {
  return {
    providers: [
      {
        providerId: 'codex',
        displayName: 'Codex',
        status,
        version: null,
        models: []
      }
    ]
  };
}

function workflow(id = '33333333-3333-4333-8333-333333333333', nodes: any[] = [], projectId = project().id) {
  return { id, projectId, name: 'Full Testing', nodes, createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z' };
}

function node(id: string, targetId: string, dependsOnNodeIds: string[] = [], x = 10, y = 20) {
  return { id, targetId, dependsOnNodeIds, position: { x, y } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

function api(overrides = {}) {
  const agents = [
    agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
    agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Reviewer'),
    agent('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'Tester')
  ];
  return {
    listProjects: vi.fn(() => Promise.resolve([project()])),
    createProject: vi.fn(() => Promise.resolve(project('22222222-2222-4222-8222-222222222222', 'Forge AI'))),
    getRuntime: vi.fn(() => Promise.resolve(runtime())),
    listProjectAgents: vi.fn(() => Promise.resolve(agents)),
    createAgent: vi.fn(() => Promise.resolve(agent('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'Analyzer'))),
    getAgent: vi.fn((agentId: string) => Promise.resolve(agents.find((item) => item.id === agentId) || agents[0])),
    updateAgent: vi.fn(() => Promise.resolve(agents[0])),
    listProjectWorkflows: vi.fn(() => Promise.resolve([workflow()])),
    createWorkflow: vi.fn(() => Promise.resolve(workflow('44444444-4444-4444-8444-444444444444'))),
    getWorkflow: vi.fn((workflowId: string) => Promise.resolve(workflow(workflowId))),
    updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes))),
    ...overrides
  };
}

async function mountedPage(fakeApi = api()) {
  const dom = agentProjectsDom();
  const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
  page.mount();
  await flushAsync();
  return { dom, page, fakeApi };
}

async function openedProject(fakeApi = api()) {
  const context = await mountedPage(fakeApi);
  await context.page.openProject(project().id);
  await flushAsync();
  return context;
}

async function openedBuilder(fakeApi = api()) {
  const context = await openedProject(fakeApi);
  await context.page.openWorkflowBuilder('33333333-3333-4333-8333-333333333333');
  await flushAsync();
  return context;
}

function setRandomUuids(dom: JSDOM, values: string[]) {
  Object.defineProperty(dom.window, 'crypto', {
    value: { randomUUID: vi.fn(() => values.shift()) },
    configurable: true
  });
}

function pointer(dom: JSDOM, type: string, x: number, y: number) {
  return new dom.window.MouseEvent(type, { clientX: x, clientY: y, bubbles: true, cancelable: true });
}

function selectValue(dom: JSDOM, id: string, value: string) {
  const select = dom.window.document.getElementById(id) as HTMLSelectElement;
  select.value = value;
  select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
}

describe('Agent projects page', () => {
  it('navigation exposes Projects as primary and keeps Agent Runtime separate', () => {
    const dom = agentProjectsDom();
    bootstrapOperatorConsole({ document: dom.window.document, window: dom.window, http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } });
    expect(dom.window.document.querySelector('.sidebar-link.active')?.textContent).toContain('Projects');
    expect(dom.window.document.body.textContent).toContain('Agent Runtime');
    expect(consoleSourceText()).not.toContain('Agents V2');
  });

  it('Projects index renders only Projects', async () => {
    const { dom } = await mountedPage();
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Builder')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2ProjectsList')?.textContent).toContain('Sitionix');
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.textContent).not.toContain('New Agent');
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.textContent).not.toContain('New Workflow');
  });

  it('creating Project adds and opens the new Project', async () => {
    const newProject = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([project(), newProject])),
      createProject: vi.fn(() => Promise.resolve(newProject))
    });
    const { dom, page } = await mountedPage(fakeApi);

    dom.window.document.getElementById('agentsV2CreateProject')?.click();
    (dom.window.document.getElementById('agentsV2ProjectName') as HTMLInputElement).value = 'Forge AI';
    dom.window.document.getElementById('agentsV2ProjectForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProject).toHaveBeenCalledWith({ name: 'Forge AI' });
    expect(page.state.selectedProjectId).toBe(newProject.id);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('agentsV2ProjectTitle')?.textContent).toContain('Forge AI');
  });

  it('opening Project switches to workspace and back returns to Projects', async () => {
    const { dom, page } = await openedProject();
    expect(dom.window.document.getElementById('agentsV2ProjectCrumbs')?.textContent).toBe('Projects / Sitionix');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');

    page.showProjectsIndex();
    expect(page.state.selectedProjectId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.classList.contains('hidden')).toBe(false);
  });

  it('creates and edits agents without dependency fields in payloads', async () => {
    const { dom, page, fakeApi } = await openedProject();

    await page.openAgentModal();
    (dom.window.document.getElementById('agentsV2AgentName') as HTMLInputElement).value = 'Analyzer';
    (dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value = 'Analyze changes.';
    selectValue(dom, 'agentsV2AgentProvider', 'codex');
    selectValue(dom, 'agentsV2AgentModel', 'discovered-model');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createAgent).toHaveBeenCalledWith(project().id, expect.not.objectContaining({ dependsOnAgentIds: expect.anything() }));
    expect(fakeApi.createAgent).toHaveBeenCalledWith(project().id, expect.objectContaining({
      name: 'Analyzer',
      instructions: 'Analyze changes.',
      outputSchema: { type: 'object', properties: {} },
      model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'medium' }
    }));

    await page.openAgentModal('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.updateAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', expect.not.objectContaining({ dependsOnAgentIds: expect.anything() }));
  });

  it('editing Agent can change the saved model selection', async () => {
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.resolve({
        providers: [{
          providerId: 'codex',
          displayName: 'Codex',
          status: 'READY',
          version: 'codex 1.0.0',
          models: [
            {
              modelId: 'discovered-model',
              displayName: 'Discovered Model',
              description: 'Current saved model',
              efforts: [{ effortId: 'medium', description: 'Medium' }]
            },
            {
              modelId: 'new-model',
              displayName: 'New Model',
              description: 'Replacement model',
              efforts: [{ effortId: 'xhigh', description: 'Maximum reasoning' }]
            }
          ]
        }]
      }))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    selectValue(dom, 'agentsV2AgentModel', 'new-model');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.updateAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', expect.objectContaining({
      model: { providerId: 'codex', modelId: 'new-model', effortId: 'xhigh' }
    }));
  });

  it('editing Agent refreshes runtime catalog so newly available models can be selected', async () => {
    const refreshedRuntime = {
      providers: [{
        providerId: 'codex',
        displayName: 'Codex',
        status: 'READY',
        version: 'codex 1.0.1',
        models: [
          {
            modelId: 'discovered-model',
            displayName: 'Discovered Model',
            description: 'Current saved model',
            efforts: [{ effortId: 'medium', description: 'Medium' }]
          },
          {
            modelId: 'new-model',
            displayName: 'New Model',
            description: 'Model discovered after workspace load',
            efforts: [{ effortId: 'xhigh', description: 'Maximum reasoning' }]
          }
        ]
      }]
    };
    const fakeApi = api({
      getRuntime: vi.fn()
        .mockResolvedValueOnce(runtime())
        .mockResolvedValueOnce(refreshedRuntime)
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    selectValue(dom, 'agentsV2AgentModel', 'new-model');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.getRuntime).toHaveBeenCalledTimes(2);
    expect(fakeApi.updateAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', expect.objectContaining({
      model: { providerId: 'codex', modelId: 'new-model', effortId: 'xhigh' }
    }));
  });

  it('new Agent modal keeps provider model and effort blank after runtime loads', async () => {
    const { dom, page } = await openedProject();

    await page.openAgentModal();
    const provider = dom.window.document.getElementById('agentsV2AgentProvider') as HTMLSelectElement;
    const model = dom.window.document.getElementById('agentsV2AgentModel') as HTMLSelectElement;
    const effort = dom.window.document.getElementById('agentsV2AgentEffort') as HTMLSelectElement;

    expect(provider.value).toBe('');
    expect(model.value).toBe('');
    expect(effort.value).toBe('');
    expect(provider.disabled).toBe(false);
    expect(model.disabled).toBe(true);
    expect(effort.disabled).toBe(true);

    selectValue(dom, 'agentsV2AgentProvider', 'codex');
    expect(provider.value).toBe('codex');
    expect(model.value).toBe('');
    expect(model.disabled).toBe(false);
    expect(effort.value).toBe('');
    expect(effort.disabled).toBe(true);
  });

  it('effort picker renders effort id before description after explicit model selection', async () => {
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.resolve({
        providers: [{
          providerId: 'codex',
          displayName: 'Codex',
          status: 'READY',
          version: 'codex 1.0.0',
          models: [{
            modelId: 'discovered-model',
            displayName: 'Discovered Model',
            description: 'Live model',
            efforts: [{ effortId: 'xhigh', description: 'Maximum reasoning' }]
          }]
        }]
      }))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal();
    selectValue(dom, 'agentsV2AgentProvider', 'codex');
    selectValue(dom, 'agentsV2AgentModel', 'discovered-model');

    const effort = dom.window.document.getElementById('agentsV2AgentEffort') as HTMLSelectElement;
    expect([...effort.options].map((option) => option.textContent || '')).toContain('xhigh - Maximum reasoning');
    expect([...effort.options].some((option) => option.textContent === 'Maximum reasoning')).toBe(false);
  });

  it('Agent cards render saved model metadata and effort tone classes without detail N+1', async () => {
    const agents = [
      { ...agent('low-agent', 'Low'), model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'low' } },
      { ...agent('medium-agent', 'Medium'), model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'medium' } },
      { ...agent('high-agent', 'High'), model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'high' } },
      { ...agent('xhigh-agent', 'XHigh'), model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'xhigh' } },
      { ...agent('unknown-agent', 'Unknown'), model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'super-high' } },
      { ...agent('legacy-agent', 'Legacy'), model: null }
    ];
    const fakeApi = api({
      listProjectAgents: vi.fn(() => Promise.resolve(agents))
    });
    const { dom } = await openedProject(fakeApi);
    const list = dom.window.document.getElementById('agentsV2AgentsList')!;

    expect(list.textContent).toContain('Codex');
    expect(list.textContent).toContain('Discovered Model');
    expect(list.textContent).toContain('xhigh');
    expect(list.textContent).toContain('No model selected');
    expect(list.querySelector('[data-effort-tone="low"]')).not.toBeNull();
    expect(list.querySelector('[data-effort-tone="medium"]')).not.toBeNull();
    expect(list.querySelector('[data-effort-tone="high"]')).not.toBeNull();
    expect(list.querySelector('[data-effort-tone="maximum"]')).not.toBeNull();
    expect(list.querySelector('[data-effort-tone="neutral"]')).not.toBeNull();
    expect(fakeApi.getAgent).not.toHaveBeenCalled();
  });

  it('Agent cards fall back to persisted ids when runtime catalog fails', async () => {
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.reject(new Error('Runtime unavailable')))
    });
    const { dom } = await openedProject(fakeApi);
    const list = dom.window.document.getElementById('agentsV2AgentsList')!;

    expect(list.textContent).toContain('codex');
    expect(list.textContent).toContain('discovered-model');
    expect(list.textContent).toContain('medium');
  });

  it('stale saved model selection remains visible and is not silently replaced', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'old-model', effortId: 'xhigh' }
    };
    const fakeApi = api({
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);

    expect((dom.window.document.getElementById('agentsV2AgentProvider') as HTMLSelectElement).value).toBe('codex');
    expect((dom.window.document.getElementById('agentsV2AgentModel') as HTMLSelectElement).value).toBe('old-model');
    expect((dom.window.document.getElementById('agentsV2AgentEffort') as HTMLSelectElement).value).toBe('xhigh');
    expect(dom.window.document.getElementById('agentsV2AgentModel')?.textContent).toContain('old-model (stale)');
    expect(dom.window.document.getElementById('agentsV2AgentEffort')?.textContent).toContain('xhigh (stale)');

    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.updateAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentModalError')?.textContent).toContain('Select a current ready model.');
  });

  it('editing Agent shows unavailable provider as unavailable instead of stale', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'gpt-5.4-mini', effortId: 'medium' }
    };
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.resolve(unavailableCodexRuntime())),
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);

    const provider = dom.window.document.getElementById('agentsV2AgentProvider') as HTMLSelectElement;
    const model = dom.window.document.getElementById('agentsV2AgentModel') as HTMLSelectElement;
    const effort = dom.window.document.getElementById('agentsV2AgentEffort') as HTMLSelectElement;
    expect(provider.textContent).toContain('Codex (unavailable)');
    expect(provider.textContent).not.toContain('codex (stale)');
    expect(model.value).toBe('gpt-5.4-mini');
    expect(model.textContent).toContain('gpt-5.4-mini (unavailable)');
    expect(effort.value).toBe('medium');
    expect(effort.textContent).toContain('medium (unavailable)');
    expect(dom.window.document.getElementById('agentsV2AgentRuntimeState')?.textContent).toContain('Codex runtime unavailable.');

    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.updateAgent).not.toHaveBeenCalled();
  });

  it('editing Agent shows degraded provider as degraded instead of stale', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'gpt-5.4-mini', effortId: 'medium' }
    };
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.resolve(unavailableCodexRuntime('DEGRADED'))),
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);

    expect(dom.window.document.getElementById('agentsV2AgentProvider')?.textContent).toContain('Codex (degraded)');
    expect(dom.window.document.getElementById('agentsV2AgentProvider')?.textContent).not.toContain('codex (stale)');
    expect(dom.window.document.getElementById('agentsV2AgentRuntimeState')?.textContent).toContain('Codex runtime degraded.');
  });

  it('editing Agent still marks saved provider stale when provider is absent from runtime', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'gpt-5.4-mini', effortId: 'medium' }
    };
    const fakeApi = api({
      getRuntime: vi.fn(() => Promise.resolve({ providers: [] })),
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);

    expect(dom.window.document.getElementById('agentsV2AgentProvider')?.textContent).toContain('codex (stale)');
    expect(dom.window.document.getElementById('agentsV2AgentRuntimeState')?.textContent).toContain('No ready model providers available.');
  });

  it('editing Agent refreshes from unavailable runtime to READY and makes current models selectable', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'gpt-5.4-mini', effortId: 'medium' }
    };
    const fakeApi = api({
      getRuntime: vi.fn()
        .mockResolvedValueOnce(unavailableCodexRuntime())
        .mockResolvedValueOnce(runtime()),
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);

    const provider = dom.window.document.getElementById('agentsV2AgentProvider') as HTMLSelectElement;
    const model = dom.window.document.getElementById('agentsV2AgentModel') as HTMLSelectElement;
    expect(provider.disabled).toBe(false);
    expect(provider.textContent).toContain('Codex');
    expect(model.disabled).toBe(false);
    expect(model.textContent).toContain('Discovered Model');
    expect(dom.window.document.getElementById('agentsV2AgentRuntimeState')?.textContent).toBe('');
  });

  it('editing Agent can replace a stale saved model with a current model', async () => {
    const staleAgent = {
      ...agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect'),
      model: { providerId: 'codex', modelId: 'old-model', effortId: 'xhigh' }
    };
    const fakeApi = api({
      getAgent: vi.fn(() => Promise.resolve(staleAgent))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openAgentModal(staleAgent.id);
    selectValue(dom, 'agentsV2AgentModel', 'discovered-model');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.updateAgent).toHaveBeenCalledWith(staleAgent.id, expect.objectContaining({
      model: { providerId: 'codex', modelId: 'discovered-model', effortId: 'medium' }
    }));
  });

  it('effort tone helper maps known intensity ids and falls back neutrally', () => {
    expect(effortTone('low')).toBe('low');
    expect(effortTone('minimal')).toBe('low');
    expect(effortTone('medium')).toBe('medium');
    expect(effortTone('high')).toBe('high');
    expect(effortTone('xhigh')).toBe('maximum');
    expect(effortTone('super-high')).toBe('neutral');
  });

  it('rejects malformed and non-object Agent Output JSON without API calls', async () => {
    const { dom, page, fakeApi } = await openedProject();
    await page.openAgentModal();

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '{';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toContain('not valid JSON');

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '[]';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toBe('Output JSON must be a JSON object.');
  });

  it('creates Workflow from Project and opens the dedicated builder', async () => {
    const { dom, fakeApi } = await openedProject();

    dom.window.document.getElementById('agentsV2CreateWorkflow')?.click();
    (dom.window.document.getElementById('agentsV2WorkflowName') as HTMLInputElement).value = 'PR Review';
    dom.window.document.getElementById('agentsV2WorkflowForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createWorkflow).toHaveBeenCalledWith(project().id, { name: 'PR Review' });
    expect(fakeApi.getWorkflow).toHaveBeenCalledWith('44444444-4444-4444-8444-444444444444');
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Builder')?.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('agentsV2BuilderCrumbs')?.textContent).toContain('Projects / Sitionix / Workflows');
  });

  it('stale previous Project responses cannot overwrite current Project', async () => {
    const dom = agentProjectsDom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const firstAgents = deferred<any[]>();
    const secondAgents = deferred<any[]>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => projectId === projectOne.id ? firstAgents.promise : secondAgents.promise),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)]))
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();

    const firstOpen = page.openProject(projectOne.id);
    const secondOpen = page.openProject(projectTwo.id);
    secondAgents.resolve([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend', projectTwo.id)]);
    await secondOpen;
    firstAgents.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect', projectOne.id)]);
    await firstOpen;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.agentsProjectId).toBe(projectTwo.id);
    expect(page.state.openWorkflowId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Backend');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).not.toContain('Architect');
  });

  it('stale Workflow details do not open after moving to another Project', async () => {
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const firstWorkflowDetail = deferred<any>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => Promise.resolve([agent(`agent-${projectId}`, projectId === projectOne.id ? 'Architect' : 'Backend', projectId)])),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)])),
      getWorkflow: vi.fn(() => firstWorkflowDetail.promise)
    });
    const { dom, page } = await openedProject(fakeApi);

    const staleOpen = page.openWorkflowBuilder('wf-1');
    await page.openProject(projectTwo.id);
    firstWorkflowDetail.resolve(workflow('wf-1', [], projectOne.id));
    await staleOpen;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.openWorkflowId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2Builder')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(false);
  });

  it('same Agent can be added multiple times as distinct Node IDs', async () => {
    const { dom, page } = await openedBuilder();
    setRandomUuids(dom, ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']);

    page.workflowBuilder.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    page.workflowBuilder.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');

    expect(page.workflowBuilder.workflow.nodes).toHaveLength(2);
    expect(page.workflowBuilder.workflow.nodes[0].id).not.toBe(page.workflowBuilder.workflow.nodes[1].id);
    expect(page.workflowBuilder.workflow.nodes[0].targetId).toBe(page.workflowBuilder.workflow.nodes[1].targetId);
  });

  it('Node body drag changes position without rebuilding Node DOM', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20)]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    const element = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-1"]')!;

    element.dispatchEvent(pointer(dom, 'pointerdown', 10, 20));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 60, 80));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 60, 80));

    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 60, y: 80 });
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')).toBe(element);
  });

  it('Node body drag updates only connected edge geometry without rebuilding unrelated edge DOM', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', ['a'], 260, 20),
      node('c', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', [], 10, 220),
      node('d', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', ['c'], 260, 220)
    ]))) });
    const { dom } = await openedBuilder(fakeApi);
    const movingEdge = dom.window.document.querySelector<SVGGElement>('[data-edge-source="a"][data-edge-target="b"]')!;
    const unrelatedEdge = dom.window.document.querySelector<SVGGElement>('[data-edge-source="c"][data-edge-target="d"]')!;
    const movingPathBefore = movingEdge.querySelector('.edge-visible')!.getAttribute('d');
    const unrelatedPathBefore = unrelatedEdge.querySelector('.edge-visible')!.getAttribute('d');
    const nodeElement = dom.window.document.querySelector<HTMLElement>('[data-node-id="a"]')!;

    nodeElement.dispatchEvent(pointer(dom, 'pointerdown', 10, 20));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 60, 80));

    const movingEdgeAfter = dom.window.document.querySelector<SVGGElement>('[data-edge-source="a"][data-edge-target="b"]')!;
    const unrelatedEdgeAfter = dom.window.document.querySelector<SVGGElement>('[data-edge-source="c"][data-edge-target="d"]')!;
    expect(movingEdgeAfter).toBe(movingEdge);
    expect(movingEdgeAfter.querySelector('.edge-visible')!.getAttribute('d')).not.toBe(movingPathBefore);
    expect(unrelatedEdgeAfter).toBe(unrelatedEdge);
    expect(unrelatedEdgeAfter.querySelector('.edge-visible')!.getAttribute('d')).toBe(unrelatedPathBefore);
  });

  it('dragging from output handle previews and dropping on target input creates dependency', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<HTMLElement>('[data-node-output="node-1"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 300, 120));
    expect(dom.window.document.querySelector('.workflow-edge-preview')?.getAttribute('d')).toContain('300 120');

    dom.window.document.querySelector<HTMLElement>('[data-node-input="node-2"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.nodes[1].dependsOnNodeIds).toEqual(['node-1']);
    expect(dom.window.document.querySelector('.workflow-edge-preview')).toBeNull();
    expect(dom.window.document.getElementById('agentsV2WorkflowEdges')?.innerHTML).toContain('workflow-edge');
  });

  it('dropping connection on empty canvas cancels', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<HTMLElement>('[data-node-output="node-1"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 500, 500));

    expect(page.workflowBuilder.workflow.nodes[1].dependsOnNodeIds).toEqual([]);
    expect(dom.window.document.querySelector('.workflow-edge-preview')).toBeNull();
  });

  it('self and duplicate connections are rejected locally', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', ['node-1'])
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<HTMLElement>('[data-node-output="node-1"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.querySelector<HTMLElement>('[data-node-input="node-1"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.nodes[0].dependsOnNodeIds).toEqual([]);

    dom.window.document.querySelector<HTMLElement>('[data-node-output="node-1"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.querySelector<HTMLElement>('[data-node-input="node-2"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.nodes[1].dependsOnNodeIds).toEqual(['node-1']);
  });

  it('fan-out and fan-in connections update target dependsOnNodeIds only', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
      node('c', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dragConnect(dom, 'a', 'b');
    dragConnect(dom, 'a', 'c');
    dragConnect(dom, 'b', 'c');

    expect(page.workflowBuilder.workflow.nodes.find((item: any) => item.id === 'b').dependsOnNodeIds).toEqual(['a']);
    expect(page.workflowBuilder.workflow.nodes.find((item: any) => item.id === 'c').dependsOnNodeIds).toEqual(['a', 'b']);
  });

  it('existing edge can be removed directly and Node removal cleans references', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', ['a'])
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<SVGElement>('[data-remove-connection][data-source-node-id="a"][data-target-node-id="b"]')!
      .dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    expect(page.workflowBuilder.workflow.nodes[1].dependsOnNodeIds).toEqual([]);
    dragConnect(dom, 'a', 'b');
    page.workflowBuilder.removeNode('a');
    expect(page.workflowBuilder.workflow.nodes).toHaveLength(1);
    expect(page.workflowBuilder.workflow.nodes[0].dependsOnNodeIds).toEqual([]);
  });

  it('save submits complete graph, reload restores positions, and backend cycle errors are shown', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 44, 55)]))),
      updateWorkflow: vi.fn()
        .mockResolvedValueOnce(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 80, 90)]))
        .mockRejectedValueOnce(new Error('WORKFLOW_GRAPH_CYCLE: Workflow graph contains a cycle.'))
    });
    const { dom, page } = await openedBuilder(fakeApi);
    page.workflowBuilder.workflow.nodes[0].position = { x: 80, y: 90 };

    await page.workflowBuilder.save();
    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', {
      name: 'Full Testing',
      nodes: [{
        id: 'node-1',
        targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        dependsOnNodeIds: [],
        position: { x: 80, y: 90 }
      }]
    });
    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 80, y: 90 });

    await page.workflowBuilder.save();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('WORKFLOW_GRAPH_CYCLE');
  });

  it('UUID fallback produces valid UUIDs and missing crypto fails clearly', async () => {
    const { dom, page } = await openedBuilder();
    Object.defineProperty(dom.window, 'crypto', {
      value: { getRandomValues: (bytes: Uint8Array) => bytes.fill(7) },
      configurable: true
    });
    page.workflowBuilder.addNode('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(page.workflowBuilder.workflow.nodes[0].id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

    Object.defineProperty(dom.window, 'crypto', { value: {}, configurable: true });
    page.workflowBuilder.addNode('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('UUID generation unavailable');
  });

  it('Console API calls Nexus infrastructure routes only', () => {
    const http = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
    const client = createAgentProjectsApi(http);
    client.listProjects();
    client.createProject({ name: 'Sitionix' });
    client.listProjectAgents(project().id);
    client.createAgent(project().id, { name: 'Agent', instructions: 'Do work.', outputSchema: {} });
    client.getAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    client.updateAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', { name: 'Agent', instructions: 'Do work.', outputSchema: {} });
    client.listProjectWorkflows(project().id);
    client.createWorkflow(project().id, { name: 'Full Testing' });
    client.getWorkflow('33333333-3333-4333-8333-333333333333');
    client.updateWorkflow('33333333-3333-4333-8333-333333333333', { name: 'Full Testing', nodes: [] });

    const calls = [...http.get.mock.calls, ...http.post.mock.calls, ...http.put.mock.calls].map(([path]) => path);
    expect(calls.every((path) => path.startsWith('/agents'))).toBe(true);
    expect(consoleSourceText()).not.toContain('7091');
    expect(consoleSourceText()).not.toContain('FORGE_AGENT');
    expect(JSON.stringify(http.post.mock.calls)).not.toContain('dependsOnAgentIds');
  });
});

function dragConnect(dom: JSDOM, source: string, target: string) {
  dom.window.document.querySelector<HTMLElement>(`[data-node-output="${source}"]`)!
    .dispatchEvent(pointer(dom, 'pointerdown', 200, 40));
  dom.window.document.querySelector<HTMLElement>(`[data-node-input="${target}"]`)!
    .dispatchEvent(pointer(dom, 'pointerup', 20, 40));
}

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
