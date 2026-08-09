import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { AgentsV2Page } from '../src/operator/agents-v2-page.js';
import { createAgentsV2Api } from '../src/operator/agents-v2-api.js';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';

function agentsDom() {
  return new JSDOM(`<!doctype html>
    <body data-page="agents-v2">
      <button id="agentsV2CreateProject" type="button"></button>
      <div id="agentsV2ProjectsError" class="hidden"></div>
      <div id="agentsV2ProjectsList"></div>
      <section id="agentsV2Workspace">
        <p id="agentsV2ProjectCrumbs"></p>
        <h2 id="agentsV2ProjectTitle"></h2>
        <button id="agentsV2AgentsTab" type="button"></button>
        <button id="agentsV2WorkflowsTab" type="button"></button>
        <section id="agentsV2AgentsPane">
          <button id="agentsV2CreateAgent" type="button"></button>
          <div id="agentsV2AgentsError" class="hidden"></div>
          <div id="agentsV2AgentsList"></div>
        </section>
        <section id="agentsV2WorkflowsPane">
          <button id="agentsV2CreateWorkflow" type="button"></button>
          <div id="agentsV2WorkflowsError" class="hidden"></div>
          <div id="agentsV2WorkflowsList"></div>
        </section>
      </section>
      <section id="agentsV2Builder" class="hidden">
        <p id="agentsV2BuilderCrumbs"></p>
        <h2 id="agentsV2BuilderTitle"></h2>
        <button id="agentsV2BuilderBack" type="button"></button>
        <div id="agentsV2WorkflowBuilderError" class="hidden"></div>
        <div id="agentsV2WorkflowAgentList"></div>
        <div id="agentsV2WorkflowCanvas">
          <svg id="agentsV2WorkflowEdges"></svg>
          <div id="agentsV2WorkflowNodes"></div>
        </div>
        <button id="agentsV2WorkflowSave" type="button"></button>
      </section>
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
          <button id="agentsV2AgentCancel" type="button"></button>
          <button id="agentsV2AgentSave" type="submit"></button>
        </form>
      </dialog>
      <dialog id="agentsV2WorkflowDialog">
        <form id="agentsV2WorkflowForm">
          <div id="agentsV2WorkflowModalError" class="hidden"></div>
          <input id="agentsV2WorkflowName">
          <button id="agentsV2WorkflowCancel" type="button"></button>
          <button id="agentsV2WorkflowCreateSave" type="submit"></button>
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

function agent(id: string, name: string, projectId = project().id) {
  return {
    id,
    projectId,
    name,
    instructions: `${name} instructions`,
    outputSchema: { type: 'object' },
    createdAt: '2026-08-04T00:00:00Z',
    updatedAt: '2026-08-04T00:00:00Z'
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
    agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Reviewer')
  ];
  return {
    listProjects: vi.fn(() => Promise.resolve([project()])),
    createProject: vi.fn(() => Promise.resolve(project('22222222-2222-4222-8222-222222222222', 'Forge AI'))),
    listProjectAgents: vi.fn(() => Promise.resolve(agents)),
    createAgent: vi.fn(() => Promise.resolve(agent('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'Analyzer'))),
    getAgent: vi.fn((agentId: string) => Promise.resolve(agents.find((item) => item.id === agentId) || agents[0])),
    updateAgent: vi.fn(() => Promise.resolve(agents[0])),
    listProjectWorkflows: vi.fn(() => Promise.resolve([workflow()])),
    createWorkflow: vi.fn(() => Promise.resolve(workflow('44444444-4444-4444-8444-444444444444'))),
    getWorkflow: vi.fn(() => Promise.resolve(workflow())),
    updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes))),
    ...overrides
  };
}

describe('Agents page', () => {
  it('navigation exposes Agents as primary and removes Agents V2 copy', () => {
    const dom = agentsDom();
    bootstrapOperatorConsole({ document: dom.window.document, window: dom.window, http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } });
    expect(dom.window.document.body.textContent).toContain('Agents');
    expect(dom.window.document.body.textContent).toContain('Agent Runtime');
    expect(consoleSourceText()).not.toContain('Agents V2');
  });

  it('project selection loads agents and workflows while actions are scoped to current data', async () => {
    const dom = agentsDom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();

    await page.selectProject(project().id);

    expect(fakeApi.listProjectAgents).toHaveBeenCalledWith(project().id);
    expect(fakeApi.listProjectWorkflows).toHaveBeenCalledWith(project().id);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');
    expect((dom.window.document.getElementById('agentsV2CreateAgent') as HTMLButtonElement).disabled).toBe(false);
    expect((dom.window.document.getElementById('agentsV2CreateWorkflow') as HTMLButtonElement).disabled).toBe(false);
  });

  it('creates and edits agents without dependency fields in payloads', async () => {
    const dom = agentsDom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);

    await page.openAgentModal();
    (dom.window.document.getElementById('agentsV2AgentName') as HTMLInputElement).value = 'Analyzer';
    (dom.window.document.getElementById('agentsV2AgentInstructions') as HTMLTextAreaElement).value = 'Analyze changes.';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createAgent).toHaveBeenCalledWith(project().id, expect.not.objectContaining({ dependsOnAgentIds: expect.anything() }));
    expect(fakeApi.createAgent).toHaveBeenCalledWith(project().id, expect.objectContaining({
      name: 'Analyzer',
      instructions: 'Analyze changes.',
      outputSchema: { type: 'object', properties: {} }
    }));

    await page.openAgentModal('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.updateAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', expect.not.objectContaining({ dependsOnAgentIds: expect.anything() }));
  });

  it('creates workflow and opens the visual builder', async () => {
    const dom = agentsDom();
    const fakeApi = api();
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);

    dom.window.document.getElementById('agentsV2CreateWorkflow')?.click();
    (dom.window.document.getElementById('agentsV2WorkflowName') as HTMLInputElement).value = 'PR Review';
    dom.window.document.getElementById('agentsV2WorkflowForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createWorkflow).toHaveBeenCalledWith(project().id, { name: 'PR Review' });
    expect(fakeApi.getWorkflow).toHaveBeenCalledWith('44444444-4444-4444-8444-444444444444');
    expect(dom.window.document.getElementById('agentsV2Builder')?.classList.contains('hidden')).toBe(false);
  });

  it('adds the same agent twice as distinct node IDs and connects by node ID', async () => {
    const dom = agentsDom();
    Object.defineProperty(dom.window, 'crypto', { value: { randomUUID: vi.fn().mockReturnValueOnce('node-1').mockReturnValueOnce('node-2') } });
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow())) });
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openWorkflowBuilder('33333333-3333-4333-8333-333333333333');

    page.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    page.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    page.startConnection('node-1');
    page.connectTo('node-2');

    expect(page.state.draftWorkflow.nodes).toHaveLength(2);
    expect(page.state.draftWorkflow.nodes[0].targetId).toBe(page.state.draftWorkflow.nodes[1].targetId);
    expect(page.state.draftWorkflow.nodes[1].dependsOnNodeIds).toEqual(['node-1']);
    expect(dom.window.document.getElementById('agentsV2WorkflowEdges')?.innerHTML).toContain('path');
  });

  it('removes connections and removing a node cleans incoming dependency references', async () => {
    const dom = agentsDom();
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', ['node-1'])
    ]))) });
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openWorkflowBuilder('wf');

    page.removeConnection('node-1', 'node-2');
    expect(page.state.draftWorkflow.nodes[1].dependsOnNodeIds).toEqual([]);
    page.startConnection('node-1');
    page.connectTo('node-2');
    page.removeNode('node-1');
    expect(page.state.draftWorkflow.nodes).toHaveLength(1);
    expect(page.state.draftWorkflow.nodes[0].dependsOnNodeIds).toEqual([]);
  });

  it('dragging nodes updates position and save sends the complete graph payload', async () => {
    const dom = agentsDom();
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20)]))) });
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openWorkflowBuilder('wf');

    const element = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-1"]');
    element?.dispatchEvent(new dom.window.MouseEvent('mousedown', { clientX: 10, clientY: 20, bubbles: true }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('mousemove', { clientX: 60, clientY: 80, bubbles: true }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('mouseup', { bubbles: true }));
    await page.saveWorkflow();

    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', {
      name: 'Full Testing',
      nodes: [{
        id: 'node-1',
        targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        dependsOnNodeIds: [],
        position: { x: 60, y: 80 }
      }]
    });
  });

  it('reloads workflow positions and displays backend cycle errors clearly', async () => {
    const dom = agentsDom();
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 44, 55)]))),
      updateWorkflow: vi.fn(() => Promise.reject(new Error('Workflow graph contains a cycle.')))
    });
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.selectProject(project().id);
    await page.openWorkflowBuilder('wf');

    expect(page.state.draftWorkflow.nodes[0].position).toEqual({ x: 44, y: 55 });
    await page.saveWorkflow();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('cycle');
  });

  it('stale project responses cannot overwrite current project data', async () => {
    const dom = agentsDom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const firstAgents = deferred<any[]>();
    const secondAgents = deferred<any[]>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => projectId === projectOne.id ? firstAgents.promise : secondAgents.promise),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)]))
    });
    const page = new AgentsV2Page({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();

    const first = page.selectProject(projectOne.id);
    const second = page.selectProject(projectTwo.id);
    secondAgents.resolve([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Backend', projectTwo.id)]);
    await second;
    firstAgents.resolve([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect', projectOne.id)]);
    await first;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.agentsProjectId).toBe(projectTwo.id);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Backend');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).not.toContain('Architect');
  });

  it('Console API calls Nexus infrastructure routes only', () => {
    const http = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
    const client = createAgentsV2Api(http);
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
