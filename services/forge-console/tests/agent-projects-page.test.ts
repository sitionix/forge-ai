import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

function task(
  id = '55555555-5555-4555-8555-555555555555',
  executionStatus = 'SUCCEEDED',
  projectId = project().id,
  workflowId = workflow().id,
  workflowName = workflow().name
) {
  return {
    id,
    projectId,
    title: 'Check calculation',
    workflowId,
    workflowName,
    latestWorkflowRunId: '66666666-6666-4666-8666-666666666666',
    executionStatus,
    createdAt: '2026-08-04T01:02:03Z',
    updatedAt: '2026-08-04T01:02:03Z'
  };
}

function taskPage(items: any[], page = 0, size = 20, totalItems = items.length, totalPages = Math.ceil(totalItems / size)) {
  return { items, page, size, totalItems, totalPages };
}

function taskRun(id: string, status: string, createdAt: string, workflowName = 'Full Testing') {
  return {
    id,
    taskId: task().id,
    workflowName,
    status,
    createdAt,
    startedAt: createdAt,
    finishedAt: null
  };
}

function taskDetail(id = task().id, runs: any[] = [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]) {
  return {
    ...task(id, runs[0]?.status || 'SUCCEEDED'),
    input: 'Count the letters in Sitionix.',
    runs
  };
}

function nodeRun(
  id: string,
  agentName: string,
  status: string,
  dependsOnNodeRunIds: string[] = [],
  x = 10,
  y = 20,
  output: any = null,
  failure: any = null
) {
  return {
    id,
    sourceNodeId: `source-${id}`,
    sourceAgentId: `agent-${id}`,
    agentName,
    agentInstructions: `${agentName} instructions`,
    agentOutputSchema: { type: 'object' },
    dependsOnNodeRunIds,
    inputMode: 'DEPENDENCIES_ONLY',
    position: { x, y },
    status,
    output,
    failure,
    createdAt: '2026-08-13T10:00:00Z',
    startedAt: status === 'PENDING' || status === 'BLOCKED' ? null : '2026-08-13T10:01:00Z',
    finishedAt: status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED' ? '2026-08-13T10:02:00Z' : null
  };
}

function workflowRunDetail(id: string, status: string, nodeRuns: any[] = [], workflowName = 'Full Testing') {
  return {
    id,
    taskId: task().id,
    workflowName,
    status,
    nodeRuns,
    createdAt: '2026-08-13T10:00:00Z',
    startedAt: '2026-08-13T10:00:02Z',
    finishedAt: status === 'RUNNING' || status === 'QUEUED' ? null : '2026-08-13T10:03:00Z'
  };
}

function node(
  id: string,
  targetId: string,
  dependsOnNodeIds: string[] = [],
  x = 10,
  y = 20,
  inputMode = 'DEPENDENCIES_ONLY',
  inputs: any[] = [],
  outputs: any[] = []
) {
  return { id, targetId, dependsOnNodeIds, inputMode, inputs, outputs, position: { x, y } };
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
    deleteProject: vi.fn(() => Promise.resolve({})),
    getRuntime: vi.fn(() => Promise.resolve(runtime())),
    listProjectAgents: vi.fn(() => Promise.resolve(agents)),
    createAgent: vi.fn(() => Promise.resolve(agent('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'Analyzer'))),
    getAgent: vi.fn((agentId: string) => Promise.resolve(agents.find((item) => item.id === agentId) || agents[0])),
    updateAgent: vi.fn(() => Promise.resolve(agents[0])),
    deleteAgent: vi.fn(() => Promise.resolve({})),
    listProjectWorkflows: vi.fn(() => Promise.resolve([workflow()])),
    createWorkflow: vi.fn(() => Promise.resolve(workflow('44444444-4444-4444-8444-444444444444'))),
    getWorkflow: vi.fn((workflowId: string) => Promise.resolve(workflow(workflowId))),
    updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes))),
    deleteWorkflow: vi.fn(() => Promise.resolve({})),
    listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([task(undefined, 'SUCCEEDED', projectId)], page, size))),
    createProjectTask: vi.fn(() => Promise.resolve(task('77777777-7777-4777-8777-777777777777', 'QUEUED'))),
    getProjectTask: vi.fn((taskId: string) => Promise.resolve({ ...task(taskId), input: 'Count the letters.', runs: [] })),
    deleteProjectTask: vi.fn(() => Promise.resolve({})),
    getWorkflowRun: vi.fn((runId: string) => Promise.resolve(workflowRunDetail(runId, 'SUCCEEDED'))),
    createWorkflowRun: vi.fn(() => Promise.resolve({})),
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

function useFakeWindowTimers(dom: JSDOM) {
  dom.window.setTimeout = globalThis.setTimeout as typeof dom.window.setTimeout;
  dom.window.clearTimeout = globalThis.clearTimeout as typeof dom.window.clearTimeout;
}

describe('Agent projects page', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

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

  it('Project renders x delete control instead of visible Delete text action', async () => {
    const { dom } = await mountedPage();
    const card = dom.window.document.querySelector<HTMLElement>('.project-card')!;
    const deleteButton = card.querySelector<HTMLButtonElement>('[data-delete-project-id]')!;

    expect(deleteButton.textContent).toBe('×');
    expect(deleteButton.classList.contains('entity-delete-control')).toBe(true);
    expect(deleteButton.getAttribute('aria-label')).toBe('Delete project Sitionix');
    expect(card.textContent).toContain('Open project');
    expect(card.textContent).not.toContain('Delete');
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

  it('Project delete cancel makes no API call', async () => {
    const { dom, fakeApi } = await mountedPage();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => false), configurable: true });

    dom.window.document.querySelector<HTMLElement>(`[data-delete-project-id="${project().id}"]`)?.click();
    await flushAsync();

    expect(dom.window.confirm).toHaveBeenCalledWith('Delete project "Sitionix"?\nIts agents, workflows, tasks and execution history will be deleted.');
    expect(fakeApi.deleteProject).not.toHaveBeenCalled();
  });

  it('Project delete accepts confirmation, calls delete API, and refreshes Projects', async () => {
    const fakeApi = api({
      listProjects: vi.fn()
        .mockResolvedValueOnce([project()])
        .mockResolvedValueOnce([])
    });
    const { dom } = await mountedPage(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>(`[data-delete-project-id="${project().id}"]`)?.click();
    await flushAsync();

    expect(fakeApi.deleteProject).toHaveBeenCalledWith(project().id);
    expect(fakeApi.listProjects).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2ProjectsList')?.textContent).not.toContain('Sitionix');
  });

  it('Project delete backend conflict remains visible', async () => {
    const fakeApi = api({
      deleteProject: vi.fn(() => Promise.reject(new Error('PROJECT_HAS_ACTIVE_EXECUTIONS: Project cannot be deleted while an execution is active.')))
    });
    const { dom } = await mountedPage(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>(`[data-delete-project-id="${project().id}"]`)?.click();
    await flushAsync();

    expect(fakeApi.deleteProject).toHaveBeenCalledWith(project().id);
    expect(dom.window.document.getElementById('agentsV2ProjectsError')?.textContent).toContain('PROJECT_HAS_ACTIVE_EXECUTIONS');
  });

  it('opening Project switches to workspace and back returns to Projects', async () => {
    const { dom, page } = await openedProject();
    expect(dom.window.document.getElementById('agentsV2ProjectCrumbs')?.textContent).toBe('Projects / Sitionix');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Check calculation');

    page.showProjectsIndex();
    expect(page.state.selectedProjectId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.classList.contains('hidden')).toBe(false);
  });

  it('opening Project loads Tasks and renders title workflow status and created date', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        task('task-1', 'RUNNING', projectId, workflow().id, 'Simple Analysis')
      ], page, size)))
    });
    const { dom } = await openedProject(fakeApi);
    const list = dom.window.document.getElementById('agentsV2TasksList')!;

    expect(fakeApi.listProjectTasks).toHaveBeenCalledWith(project().id, 0, 20);
    expect(list.textContent).toContain('Check calculation');
    expect(list.textContent).toContain('Simple Analysis');
    expect(list.textContent).toContain('RUNNING');
    expect(list.textContent).toContain('Created');
    expect(list.querySelector('[data-task-status="RUNNING"]')).not.toBeNull();
    expect(list.querySelector('[data-task-id="task-1"]')?.textContent).toBe('Open');
  });

  it('Tasks render as compact rows instead of Task cards', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task('task-1', 'RUNNING', projectId, workflow().id, 'Backend Flow'), title: 'Fix auth endpoint' },
        { ...task('task-2', 'FAILED', projectId, workflow().id, 'Review Flow'), title: 'Review implementation' }
      ], page, size)))
    });
    const { dom } = await openedProject(fakeApi);
    const list = dom.window.document.getElementById('agentsV2TasksList')!;
    const rows = [...list.querySelectorAll<HTMLElement>('.agents-v2-task-row:not(.agents-v2-task-row-head)')];
    const deleteButton = rows[0]!.querySelector<HTMLButtonElement>('[data-delete-task-id="task-1"]')!;

    expect(list.classList.contains('agents-v2-task-list')).toBe(true);
    expect(list.classList.contains('agents-v2-card-grid')).toBe(false);
    expect(list.querySelector('.agents-v2-task-table')).not.toBeNull();
    expect(list.querySelector('[role="table"]')).not.toBeNull();
    expect(rows).toHaveLength(2);
    expect(rows[0]!.textContent).toContain('Fix auth endpoint');
    expect(rows[0]!.textContent).toContain('Backend Flow');
    expect(rows[0]!.textContent).toContain('RUNNING');
    expect(rows[0]!.textContent).toContain('Open');
    expect(rows[0]!.textContent).not.toContain('Delete');
    expect(deleteButton.textContent).toBe('×');
    expect(deleteButton.classList.contains('entity-delete-control')).toBe(true);
    expect(deleteButton.getAttribute('aria-label')).toBe('Delete task Fix auth endpoint');
    expect(list.querySelector('.agents-v2-card')).toBeNull();
  });

  it('Agent and Workflow render x delete controls with entity labels', async () => {
    const { dom } = await openedProject();
    const agentCard = dom.window.document.getElementById('agentsV2AgentsList')!.querySelector<HTMLElement>('.agents-v2-card')!;
    const workflowCard = dom.window.document.getElementById('agentsV2WorkflowsList')!.querySelector<HTMLElement>('.agents-v2-card')!;
    const agentDelete = agentCard.querySelector<HTMLButtonElement>('[data-delete-agent-id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]')!;
    const workflowDelete = workflowCard.querySelector<HTMLButtonElement>('[data-delete-workflow-id="33333333-3333-4333-8333-333333333333"]')!;

    expect(agentDelete.textContent).toBe('×');
    expect(agentDelete.classList.contains('entity-delete-control')).toBe(true);
    expect(agentDelete.getAttribute('aria-label')).toBe('Delete agent Architect');
    expect(agentCard.textContent).toContain('Edit');
    expect(agentCard.textContent).not.toContain('Delete');

    expect(workflowDelete.textContent).toBe('×');
    expect(workflowDelete.classList.contains('entity-delete-control')).toBe(true);
    expect(workflowDelete.getAttribute('aria-label')).toBe('Delete workflow Full Testing');
    expect(workflowCard.textContent).toContain('Open');
    expect(workflowCard.textContent).not.toContain('Delete');
  });

  it('Task pagination starts at page 0 size 20 and moves Next and Previous with disabled edge controls', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task(`task-page-${page}`, 'SUCCEEDED', projectId, workflow().id, 'Full Testing'), title: `Page ${page} task` }
      ], page, size, 40, 2)))
    });
    const { dom, page } = await openedProject(fakeApi);

    expect(page.state.tasksPage).toBe(0);
    expect(page.state.tasksPageSize).toBe(20);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledWith(project().id, 0, 20);
    expect((dom.window.document.querySelector('[data-task-page="prev"]') as HTMLButtonElement).disabled).toBe(true);
    expect((dom.window.document.querySelector('[data-task-page="next"]') as HTMLButtonElement).disabled).toBe(false);

    dom.window.document.querySelector<HTMLElement>('[data-task-page="next"]')?.click();
    await flushAsync();

    expect(page.state.tasksPage).toBe(1);
    expect(fakeApi.listProjectTasks).toHaveBeenLastCalledWith(project().id, 1, 20);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Page 2 of 2');
    expect((dom.window.document.querySelector('[data-task-page="next"]') as HTMLButtonElement).disabled).toBe(true);
    expect((dom.window.document.querySelector('[data-task-page="prev"]') as HTMLButtonElement).disabled).toBe(false);

    dom.window.document.querySelector<HTMLElement>('[data-task-page="prev"]')?.click();
    await flushAsync();

    expect(page.state.tasksPage).toBe(0);
    expect(fakeApi.listProjectTasks).toHaveBeenLastCalledWith(project().id, 0, 20);
    expect((dom.window.document.querySelector('[data-task-page="prev"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('switching Project resets the selected Task page to 0', async () => {
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => Promise.resolve([agent(`agent-${projectId}`, projectId === projectOne.id ? 'Architect' : 'Backend', projectId)])),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)])),
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task(`task-${projectId}-${page}`, 'SUCCEEDED', projectId, projectId === projectOne.id ? 'wf-1' : 'wf-2'), title: `Project ${projectId} page ${page}` }
      ], page, size, 40, 2)))
    });
    const { page } = await mountedPage(fakeApi);

    await page.openProject(projectOne.id);
    await flushAsync();
    await page.goToTaskPage(1);
    await flushAsync();
    expect(page.state.tasksPage).toBe(1);

    await page.openProject(projectTwo.id);
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.tasksPage).toBe(0);
    expect(fakeApi.listProjectTasks).toHaveBeenLastCalledWith(projectTwo.id, 0, 20);
  });

  it('creating a Task from page greater than 0 resets Tasks to page 0', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task(`task-page-${page}`, 'SUCCEEDED', projectId, workflow().id, 'Full Testing'), title: `Page ${page} task` }
      ], page, size, 40, 2)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.goToTaskPage(1);
    await flushAsync();

    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = 'Fresh task';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Run this now.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).toHaveBeenCalledTimes(1);
    expect(page.state.tasksPage).toBe(0);
    expect(fakeApi.listProjectTasks).toHaveBeenLastCalledWith(project().id, 0, 20);
  });

  it('TaskExecutionView Back preserves the Task page selected before opening', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        task(`task-page-${page}`, 'SUCCEEDED', projectId)
      ], page, size, 40, 2))),
      getProjectTask: vi.fn((taskId: string) => Promise.resolve(taskDetail(taskId, [taskRun('run-done', 'SUCCEEDED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn((runId: string) => Promise.resolve(workflowRunDetail(runId, 'SUCCEEDED')))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.goToTaskPage(1);
    await flushAsync();

    await page.openTaskExecution('task-page-1');
    await flushAsync();
    dom.window.document.getElementById('agentsV2TaskExecutionBack')?.click();
    await flushAsync();

    expect(page.state.tasksPage).toBe(1);
    expect(fakeApi.listProjectTasks).toHaveBeenLastCalledWith(project().id, 1, 20);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(false);
  });

  it('stale Task page response cannot overwrite the newly selected page', async () => {
    const pageOne = deferred<any>();
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => {
        if (page === 1) {
          return pageOne.promise;
        }
        if (page === 2) {
          return Promise.resolve(taskPage([
            { ...task('task-page-2', 'SUCCEEDED', projectId), title: 'Current page task' }
          ], page, size, 60, 3));
        }
        return Promise.resolve(taskPage([
          { ...task('task-page-0', 'SUCCEEDED', projectId), title: 'Initial page task' }
        ], page, size, 60, 3));
      })
    });
    const { dom, page } = await openedProject(fakeApi);

    const staleLoad = page.goToTaskPage(1);
    await flushAsync();
    const currentLoad = page.goToTaskPage(2);
    await currentLoad;
    pageOne.resolve(taskPage([
      { ...task('task-page-1', 'SUCCEEDED'), title: 'Stale page task' }
    ], 1, 20, 60, 3));
    await staleLoad;
    await flushAsync();

    expect(page.state.tasksPage).toBe(2);
    expect(page.state.tasksLoadedPage).toBe(2);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Current page task');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('Stale page task');
  });

  it('Task polling fetches only the currently selected Task page', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        task(`task-page-${page}`, 'RUNNING', projectId)
      ], page, size, 40, 2)))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();
    await page.goToTaskPage(1);
    await flushAsync();
    fakeApi.listProjectTasks.mockClear();

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();

    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledWith(project().id, 1, 20);
  });

  it('Task row Open switches to execution view, loads newest run, and Back refreshes Tasks', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const runs = [
      taskRun('run-old', 'FAILED', '2026-08-12T10:00:00Z'),
      taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')
    ];
    const fakeApi = api({
      listProjectTasks: vi.fn((_projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([task('task-1', 'RUNNING')], page, size))),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', runs))),
      getWorkflowRun: vi.fn((runId: string) => Promise.resolve(workflowRunDetail(runId, 'RUNNING', [
        nodeRun('node-a', 'Analyzer', 'RUNNING', [], 30, 40)
      ], 'Snapshot Workflow')))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 5000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();

    dom.window.document.querySelector<HTMLElement>('[data-task-id="task-1"]')?.click();
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2TaskExecution')?.classList.contains('hidden')).toBe(false);
    expect(fakeApi.getProjectTask).toHaveBeenCalledWith('task-1');
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledWith('run-new');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionTitle')?.textContent).toContain('Check calculation');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Count the letters in Sitionix.');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Snapshot Workflow');

    await vi.advanceTimersByTimeAsync(4999);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);

    dom.window.document.getElementById('agentsV2TaskExecutionBack')?.click();
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2TaskExecution')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(false);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
  });

  it('Execution history is newest first and selecting an older run loads that snapshot', async () => {
    const runs = [
      taskRun('run-old', 'FAILED', '2026-08-12T10:00:00Z'),
      taskRun('run-new', 'SUCCEEDED', '2026-08-13T10:00:00Z')
    ];
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', runs))),
      getWorkflowRun: vi.fn((runId: string) => Promise.resolve(workflowRunDetail(runId, runId === 'run-old' ? 'FAILED' : 'SUCCEEDED', [
        nodeRun(runId === 'run-old' ? 'old-node' : 'new-node', runId === 'run-old' ? 'Old Agent' : 'New Agent', 'SUCCEEDED')
      ], runId === 'run-old' ? 'Older Snapshot' : 'Newer Snapshot')))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const historyRows = [...dom.window.document.querySelectorAll<HTMLElement>('.execution-history-row')];
    expect(historyRows.map((row) => row.dataset.runId)).toEqual(['run-new', 'run-old']);
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Newer Snapshot');

    historyRows[1]!.click();
    await flushAsync();

    expect(fakeApi.getWorkflowRun).toHaveBeenLastCalledWith('run-old');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Older Snapshot');
    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).toContain('Old Agent');
  });

  it('Execution history preserves backend ordering when run timestamps tie', async () => {
    const runs = [
      taskRun('run-a', 'FAILED', '2026-08-13T10:00:00Z', 'First Snapshot'),
      taskRun('run-b', 'SUCCEEDED', '2026-08-13T10:00:00Z', 'Second Snapshot')
    ];
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', runs))),
      getWorkflowRun: vi.fn((runId: string) => Promise.resolve(workflowRunDetail(runId, runId === 'run-a' ? 'FAILED' : 'SUCCEEDED', [
        nodeRun(runId === 'run-a' ? 'node-a' : 'node-b', runId === 'run-a' ? 'First Agent' : 'Second Agent', 'SUCCEEDED')
      ], runId === 'run-a' ? 'First Snapshot' : 'Second Snapshot')))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const historyRows = [...dom.window.document.querySelectorAll<HTMLElement>('.execution-history-row')];
    expect(historyRows.map((row) => row.dataset.runId)).toEqual(['run-a', 'run-b']);
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledWith('run-a');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('First Snapshot');
  });

  it('Execution graph renders NodeRun snapshot positions, dependencies, statuses, and node details', async () => {
    const nodeRuns = [
      nodeRun('pending-node', 'Planner', 'PENDING', [], 20, 30),
      nodeRun('running-node', 'Analyzer', 'RUNNING', ['pending-node'], 280, 30),
      {
        ...nodeRun('success-node', 'Backend Implementer', 'SUCCEEDED', ['running-node'], 540, 30, { count: 8, valid: true }),
        inputMode: 'TASK_AND_DEPENDENCIES'
      },
      nodeRun('failed-node', 'Reviewer', 'FAILED', ['success-node'], 800, 30, null, { code: 'ASSERTION_FAILED', message: 'Expected count to match.' }),
      nodeRun('blocked-node', 'Release', 'BLOCKED', ['failed-node'], 1060, 30),
      nodeRun('cancelled-node', 'Cleanup', 'CANCELLED', ['blocked-node'], 1320, 30)
    ];
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'FAILED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'FAILED', nodeRuns, 'Snapshot Only Flow')))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    expect(fakeApi.getWorkflow).not.toHaveBeenCalled();
    expect(fakeApi.getAgent).not.toHaveBeenCalled();
    for (const status of ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED']) {
      expect(dom.window.document.querySelector(`[data-node-status="${status}"]`)).not.toBeNull();
    }
    const successNode = dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="success-node"]')!;
    expect(successNode.getAttribute('style')).toContain('left:540px');
    expect(successNode.getAttribute('style')).toContain('top:30px');
    expect(dom.window.document.querySelector('[data-edge-source="running-node"][data-edge-target="success-node"]')).not.toBeNull();

    successNode.click();
    await flushAsync();
    const details = dom.window.document.getElementById('agentsV2NodeRunDetails')!;
    expect(details.textContent).toContain('Backend Implementer');
    expect(details.textContent).toContain('Backend Implementer instructions');
    expect(details.textContent).toContain('SUCCEEDED');
    expect(details.textContent).toContain('Original task + previous outputs');
    expect(details.querySelector('pre')?.textContent).toContain('"count": 8');

    dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="pending-node"]')?.click();
    await flushAsync();
    expect(details.textContent).toContain('Original task');
    expect(details.textContent).toContain('No output yet.');

    dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="running-node"]')?.click();
    await flushAsync();
    expect(details.textContent).toContain('Previous outputs only');

    dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="failed-node"]')?.click();
    await flushAsync();
    expect(details.textContent).toContain('ASSERTION_FAILED');
    expect(details.textContent).toContain('Expected count to match.');
  });

  it('active WorkflowRun polling refreshes node output and stops at terminal status', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn()
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'RUNNING', [
          nodeRun('node-a', 'Analyzer', 'RUNNING')
        ]))
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'RUNNING', [
          nodeRun('node-a', 'Analyzer', 'SUCCEEDED', [], 10, 20, { answer: 8 }),
          nodeRun('node-b', 'Reviewer', 'RUNNING', ['node-a'], 260, 20)
        ]))
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'FAILED', [
          nodeRun('node-a', 'Analyzer', 'SUCCEEDED', [], 10, 20, { answer: 8 }),
          nodeRun('node-b', 'Reviewer', 'FAILED', ['node-a'], 260, 20, null, { code: 'BAD_RESULT', message: 'Review failed.' })
        ]))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();
    await page.openTaskExecution('task-1');
    await flushAsync();

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    expect(dom.window.document.querySelector('[data-execution-node-id="node-a"]')?.textContent).toContain('SUCCEEDED');
    dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="node-a"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('"answer": 8');

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('FAILED');
    dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="node-b"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('BAD_RESULT');

    await vi.advanceTimersByTimeAsync(3000);
    await flushAsync();
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledTimes(3);
  });

  it('transient polling failure keeps last graph visible, retries, and heals after success', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn()
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'RUNNING', [
          nodeRun('node-a', 'Analyzer', 'RUNNING')
        ]))
        .mockRejectedValueOnce(new Error('Refresh unavailable'))
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'SUCCEEDED', [
          nodeRun('node-a', 'Analyzer', 'SUCCEEDED', [], 10, 20, { answer: 8 })
        ]))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();
    await page.openTaskExecution('task-1');
    await flushAsync();

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).toContain('Analyzer');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionRefreshError')?.textContent).toContain('Refresh unavailable');

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2TaskExecutionRefreshError')?.textContent).toBe('');
    expect(dom.window.document.querySelector('[data-execution-node-id="node-a"]')?.textContent).toContain('SUCCEEDED');
  });

  it('WorkflowRun polling does not overlap and leaving Task stops execution polling', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const poll = deferred<any>();
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn()
        .mockResolvedValueOnce(workflowRunDetail('run-new', 'RUNNING', [nodeRun('node-a', 'Analyzer', 'RUNNING')]))
        .mockReturnValueOnce(poll.promise),
      listProjectTasks: vi.fn((_projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([task('task-1', 'RUNNING')], page, size)))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();
    await page.openTaskExecution('task-1');
    await flushAsync();

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    await vi.advanceTimersByTimeAsync(4000);
    await flushAsync();
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledTimes(2);

    const back = page.closeTaskExecution();
    poll.resolve(workflowRunDetail('run-new', 'RUNNING', [nodeRun('node-a', 'Analyzer', 'SUCCEEDED')]));
    await back;
    await flushAsync();
    await vi.advanceTimersByTimeAsync(3000);
    await flushAsync();
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledTimes(2);
  });

  it('Back to Project waits for in-flight Task polling before one fresh Task refresh wins', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const stalePoll = deferred<any>();
    const calls: string[] = [];
    const fakeApi = api({
      listProjectTasks: vi.fn(() => {
        calls.push(`list-${calls.length + 1}`);
        if (calls.length === 1) {
          return Promise.resolve(taskPage([task('task-1', 'RUNNING')]));
        }
        if (calls.length === 2) {
          return stalePoll.promise;
        }
        return Promise.resolve(taskPage([task('task-1', 'FAILED')]));
      }),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [
        nodeRun('node-a', 'Analyzer', 'RUNNING')
      ])))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();

    await vi.advanceTimersByTimeAsync(1000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    await page.openTaskExecution('task-1');
    await flushAsync();
    const back = page.closeTaskExecution();
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    stalePoll.resolve(taskPage([task('task-1', 'RUNNING')]));
    await back;
    await flushAsync();

    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(3);
    expect(calls).toEqual(['list-1', 'list-2', 'list-3']);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('FAILED');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('RUNNING');
  });

  it('stale Task and Run responses cannot overwrite the current execution selection', async () => {
    const taskA = deferred<any>();
    const runA = deferred<any>();
    const fakeApi = api({
      getProjectTask: vi.fn((taskId: string) => taskId === 'task-a'
        ? taskA.promise
        : Promise.resolve(taskDetail('task-b', [taskRun('run-b', 'SUCCEEDED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn((runId: string) => runId === 'run-a'
        ? runA.promise
        : Promise.resolve(workflowRunDetail(runId, 'SUCCEEDED', [nodeRun('node-b', 'Current Agent', 'SUCCEEDED')], 'Current Snapshot')))
    });
    const { dom, page } = await openedProject(fakeApi);

    const staleTaskOpen = page.openTaskExecution('task-a');
    await flushAsync();
    const currentTaskOpen = page.openTaskExecution('task-b');
    await currentTaskOpen;
    taskA.resolve(taskDetail('task-a', [taskRun('run-a', 'SUCCEEDED', '2026-08-13T11:00:00Z')]));
    await staleTaskOpen;
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2TaskExecutionTitle')?.textContent).toContain('Check calculation');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Current Snapshot');
    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).toContain('Current Agent');

    const staleRunSelect = page.taskExecutionView.selectRun('run-a');
    await flushAsync();
    const currentRunSelect = page.taskExecutionView.selectRun('run-b');
    await currentRunSelect;
    runA.resolve(workflowRunDetail('run-a', 'FAILED', [nodeRun('node-a', 'Stale Agent', 'FAILED')], 'Stale Snapshot'));
    await staleRunSelect;
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Current Snapshot');
    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).toContain('Current Agent');
    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).not.toContain('Stale Agent');
  });

  it('dispose during in-flight WorkflowRun request ignores late response and schedules no timer', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const pendingRun = deferred<any>();
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => pendingRun.promise)
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1000 }
    });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();
    const open = page.openTaskExecution('task-1');
    await flushAsync();

    page.dispose();
    pendingRun.resolve(workflowRunDetail('run-new', 'RUNNING', [nodeRun('node-a', 'Analyzer', 'RUNNING')]));
    await open;
    await flushAsync();
    await vi.advanceTimersByTimeAsync(3000);
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2ExecutionNodes')?.textContent).not.toContain('Analyzer');
    expect(fakeApi.getWorkflowRun).toHaveBeenCalledTimes(1);
  });

  it('empty Task state renders under Tasks without hiding the section', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn(() => Promise.resolve(taskPage([])))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2CreateTask')).not.toBeNull();
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('No tasks yet.');
  });

  it('New Task is disabled and explains that a Workflow is required when Project has no Workflows', async () => {
    const fakeApi = api({
      listProjectWorkflows: vi.fn(() => Promise.resolve([])),
      listProjectTasks: vi.fn(() => Promise.resolve(taskPage([])))
    });
    const { dom } = await openedProject(fakeApi);

    expect((dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(true);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Create a workflow before creating a task.');
  });

  it('Task loading failure remains scoped to Tasks', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn(() => Promise.reject(new Error('Tasks unavailable')))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');
    expect(dom.window.document.getElementById('agentsV2TasksError')?.textContent).toContain('Tasks unavailable');
    expect((dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(false);
  });

  it('Agents loading failure does not block New Task when Workflows and Tasks are current', async () => {
    const fakeApi = api({
      listProjectAgents: vi.fn(() => Promise.reject(new Error('Agents unavailable'))),
      listProjectWorkflows: vi.fn(() => Promise.resolve([workflow()])),
      listProjectTasks: vi.fn(() => Promise.resolve(taskPage([task('task-1', 'SUCCEEDED', project().id, workflow().id, 'Full Testing')])))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2AgentsError')?.textContent).toContain('Agents unavailable');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Check calculation');
    expect((dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(false);
  });

  it('Workflow loading failure keeps existing Task summaries visible but disables New Task', async () => {
    const fakeApi = api({
      listProjectWorkflows: vi.fn(() => Promise.reject(new Error('Workflows unavailable'))),
      listProjectTasks: vi.fn(() => Promise.resolve(taskPage([task('task-1', 'RUNNING', project().id, workflow().id, 'Snapshot Flow')])))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2WorkflowsError')?.textContent).toContain('Workflows unavailable');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Check calculation');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Snapshot Flow');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('RUNNING');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('Create a workflow before creating a task.');
    expect((dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(true);
  });

  it('stale Project Task responses are ignored after switching Projects', async () => {
    const dom = agentProjectsDom();
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const firstTasks = deferred<any>();
    const secondTasks = deferred<any>();
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => Promise.resolve([agent(`agent-${projectId}`, projectId === projectOne.id ? 'Architect' : 'Backend', projectId)])),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)])),
      listProjectTasks: vi.fn((projectId: string) => projectId === projectOne.id ? firstTasks.promise : secondTasks.promise)
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();

    const firstOpen = page.openProject(projectOne.id);
    const secondOpen = page.openProject(projectTwo.id);
    secondTasks.resolve(taskPage([task('task-2', 'SUCCEEDED', projectTwo.id, 'wf-2', 'Second Flow')]));
    await secondOpen;
    firstTasks.resolve(taskPage([task('task-1', 'SUCCEEDED', projectOne.id, 'wf-1', 'First Flow')]));
    await firstOpen;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.tasksProjectId).toBe(projectTwo.id);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Second Flow');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('First Flow');
  });

  it('New Task modal sends title input and workflowId through createProjectTask only', async () => {
    const workflows = [
      workflow('wf-1', [], project().id),
      { ...workflow('wf-2', [], project().id), name: 'Deploy Review' }
    ];
    const fakeApi = api({
      listProjectWorkflows: vi.fn(() => Promise.resolve(workflows)),
      listProjectTasks: vi.fn()
        .mockResolvedValueOnce(taskPage([]))
        .mockResolvedValueOnce(taskPage([task('task-created', 'QUEUED', project().id, 'wf-2', 'Deploy Review')]))
    });
    const { dom } = await openedProject(fakeApi);

    const newTask = dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement;
    expect(newTask.disabled).toBe(false);
    newTask.click();
    const workflowSelect = dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement;
    expect([...workflowSelect.options].map((option) => option.textContent)).toEqual(['Full Testing', 'Deploy Review']);
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = '  Test chain  ';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = '  Find X and pass the result forward  ';
    workflowSelect.value = 'wf-2';
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).toHaveBeenCalledWith(project().id, {
      title: 'Test chain',
      input: 'Find X and pass the result forward',
      workflowId: 'wf-2'
    });
    expect(fakeApi.createWorkflowRun).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(false);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Deploy Review');
  });

  it('failed Task creation keeps modal open and shows the controlled error', async () => {
    const fakeApi = api({
      createProjectTask: vi.fn(() => Promise.reject(new Error('EMPTY_WORKFLOW: Workflow must contain at least one node.')))
    });
    const { dom } = await openedProject(fakeApi);

    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = 'Check calculation';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Count letters.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2TaskModalError')?.textContent).toContain('EMPTY_WORKFLOW');
  });

  it('invalid Task modal input is rejected locally without creating a Task', async () => {
    const { dom, fakeApi } = await openedProject();

    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = ' ';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Count letters.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2TaskModalError')?.textContent).toContain('Enter a title, task, and workflow.');
    expect(dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(true);
  });

  it('active Task polling uses runtime config and stops after RUNNING becomes SUCCEEDED', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      listProjectTasks: vi.fn()
        .mockResolvedValueOnce(taskPage([task('task-1', 'RUNNING')]))
        .mockResolvedValueOnce(taskPage([task('task-1', 'SUCCEEDED')]))
    });
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: fakeApi,
      runtimeConfig: { activeJobPollIntervalMs: 1234 }
    });
    page.mount();
    await flushAsync();

    await page.openProject(project().id);
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('RUNNING');

    await vi.advanceTimersByTimeAsync(1233);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('SUCCEEDED');

    await vi.advanceTimersByTimeAsync(2468);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
  });

  it('background Task polling failure keeps last good RUNNING state and next success can stop polling', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      listProjectTasks: vi.fn()
        .mockResolvedValueOnce(taskPage([task('task-1', 'RUNNING')]))
        .mockRejectedValueOnce(new Error('Tasks refresh failed'))
        .mockResolvedValueOnce(taskPage([task('task-1', 'SUCCEEDED')]))
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();

    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('RUNNING');
    expect(dom.window.document.getElementById('agentsV2TasksError')?.textContent).toContain('Tasks refresh failed');

    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(3);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('SUCCEEDED');
    expect(dom.window.document.getElementById('agentsV2TasksError')?.textContent).toBe('');

    await vi.advanceTimersByTimeAsync(4000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(3);
  });

  it('dispose while a Task request is running ignores the late response and schedules no polling', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const pendingTasks = deferred<any>();
    const fakeApi = api({
      listProjectTasks: vi.fn(() => pendingTasks.promise)
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();

    const open = page.openProject(project().id);
    await flushAsync();
    page.dispose();
    pendingTasks.resolve(taskPage([task('task-1', 'RUNNING')]));
    await open;
    await flushAsync();

    expect(page.state.tasksProjectId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('RUNNING');
    await vi.advanceTimersByTimeAsync(4000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);
  });

  it('Task polling stops when leaving Project or opening Workflow builder', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const fakeApi = api({
      listProjectTasks: vi.fn(() => Promise.resolve(taskPage([task('task-1', 'RUNNING')])))
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();

    page.showProjectsIndex();
    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);

    await page.openProject(project().id);
    await flushAsync();
    await page.openWorkflowBuilder(workflow().id);
    await flushAsync();
    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
  });

  it('Task polling does not overlap and stale poll results cannot overwrite a switched Project', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Forge AI');
    const projectOnePoll = deferred<any>();
    let projectOneTaskCalls = 0;
    const fakeApi = api({
      listProjects: vi.fn(() => Promise.resolve([projectOne, projectTwo])),
      listProjectAgents: vi.fn((projectId: string) => Promise.resolve([agent(`agent-${projectId}`, projectId === projectOne.id ? 'Architect' : 'Backend', projectId)])),
      listProjectWorkflows: vi.fn((projectId: string) => Promise.resolve([workflow(projectId === projectOne.id ? 'wf-1' : 'wf-2', [], projectId)])),
      listProjectTasks: vi.fn((projectId: string) => {
        if (projectId === projectOne.id) {
          projectOneTaskCalls += 1;
          return projectOneTaskCalls === 1
            ? Promise.resolve(taskPage([task('task-1', 'RUNNING', projectOne.id, 'wf-1', 'First Flow')]))
            : projectOnePoll.promise;
        }
        return Promise.resolve(taskPage([task('task-2', 'SUCCEEDED', projectTwo.id, 'wf-2', 'Second Flow')]));
      })
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(projectOne.id);
    await flushAsync();

    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(4000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    const secondOpen = page.openProject(projectTwo.id);
    await flushAsync();
    projectOnePoll.resolve(taskPage([task('task-1', 'RUNNING', projectOne.id, 'wf-1', 'First Flow')]));
    await secondOpen;
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectTwo.id);
    expect(page.state.tasksProjectId).toBe(projectTwo.id);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Second Flow');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).not.toContain('First Flow');
  });

  it('creating a Task during an existing refresh waits for it then performs one fresh list load', async () => {
    vi.useFakeTimers();
    const dom = agentProjectsDom();
    useFakeWindowTimers(dom);
    const runningRefresh = deferred<any>();
    const fakeApi = api({
      listProjectTasks: vi.fn()
        .mockResolvedValueOnce(taskPage([task('task-1', 'RUNNING', project().id, workflow().id, 'Full Testing')]))
        .mockReturnValueOnce(runningRefresh.promise)
        .mockResolvedValueOnce(taskPage([{ ...task('task-created', 'QUEUED', project().id, workflow().id, 'Full Testing'), title: 'Fresh task' }]))
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await flushAsync();

    await vi.advanceTimersByTimeAsync(2000);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = 'Fresh task';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Run this now.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createProjectTask).toHaveBeenCalledTimes(1);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);

    runningRefresh.resolve(taskPage([task('task-1', 'RUNNING', project().id, workflow().id, 'Full Testing')]));
    await flushAsync();

    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(3);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Fresh task');
    expect(fakeApi.createWorkflowRun).not.toHaveBeenCalled();
  });

  it('Task delete cancel makes no DELETE request', async () => {
    const { dom, fakeApi } = await openedProject();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => false), configurable: true });
    const initialLoads = fakeApi.listProjectTasks.mock.calls.length;

    dom.window.document.querySelector<HTMLElement>('[data-delete-task-id="55555555-5555-4555-8555-555555555555"]')?.click();
    await flushAsync();

    expect(dom.window.confirm).toHaveBeenCalledWith('Delete task "Check calculation"?');
    expect(fakeApi.deleteProjectTask).not.toHaveBeenCalled();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(initialLoads);
  });

  it('Task delete success reloads the current Task page', async () => {
    let deleted = false;
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task(`task-page-${page}`, 'SUCCEEDED', projectId), title: deleted ? `Reloaded page ${page}` : `Original page ${page}` }
      ], page, size, 40, 2))),
      deleteProjectTask: vi.fn(() => {
        deleted = true;
        return Promise.resolve({});
      })
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.goToTaskPage(1);
    await flushAsync();
    fakeApi.listProjectTasks.mockClear();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-task-id="task-page-1"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteProjectTask).toHaveBeenCalledWith('task-page-1');
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledWith(project().id, 1, 20);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Reloaded page 1');
  });

  it('Task delete 409 leaves the row visible and shows the backend message', async () => {
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task('task-active', 'RUNNING', projectId), title: 'Active task' }
      ], page, size))),
      deleteProjectTask: vi.fn(() => Promise.reject(new Error('PROJECT_TASK_HAS_ACTIVE_EXECUTIONS: Task cannot be deleted while an execution is active.')))
    });
    const { dom } = await openedProject(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-task-id="task-active"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteProjectTask).toHaveBeenCalledWith('task-active');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Active task');
    expect(dom.window.document.getElementById('agentsV2TasksError')?.textContent).toContain('PROJECT_TASK_HAS_ACTIVE_EXECUTIONS');
  });

  it('deleting the last Task on a non-zero page loads the previous page exactly once', async () => {
    let deleted = false;
    const fakeApi = api({
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => {
        if (page === 2) {
          return Promise.resolve(taskPage(deleted ? [] : [
            { ...task('task-last', 'SUCCEEDED', projectId), title: 'Last task on page' }
          ], page, size, deleted ? 40 : 41, deleted ? 2 : 3));
        }
        return Promise.resolve(taskPage([
          { ...task(`task-page-${page}`, 'SUCCEEDED', projectId), title: `Page ${page} task` }
        ], page, size, 41, 3));
      }),
      deleteProjectTask: vi.fn(() => {
        deleted = true;
        return Promise.resolve({});
      })
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.goToTaskPage(1);
    await flushAsync();
    await page.goToTaskPage(2);
    await flushAsync();
    fakeApi.listProjectTasks.mockClear();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-task-id="task-last"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteProjectTask).toHaveBeenCalledWith('task-last');
    expect(fakeApi.listProjectTasks.mock.calls).toEqual([
      [project().id, 2, 20],
      [project().id, 1, 20]
    ]);
    expect(page.state.tasksPage).toBe(1);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Page 1 task');
  });

  it('Agent delete cancel makes no API call', async () => {
    const { dom, fakeApi } = await openedProject();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => false), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-agent-id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]')?.click();
    await flushAsync();

    expect(dom.window.confirm).toHaveBeenCalledWith('Delete agent "Architect"?');
    expect(fakeApi.deleteAgent).not.toHaveBeenCalled();
  });

  it('Agent delete accepts confirmation, calls delete API, and refreshes Agents', async () => {
    const fakeApi = api({
      listProjectAgents: vi.fn()
        .mockResolvedValueOnce([agent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Architect')])
        .mockResolvedValueOnce([agent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Reviewer')])
    });
    const { dom } = await openedProject(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-agent-id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(fakeApi.listProjectAgents).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Reviewer');
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).not.toContain('Architect');
  });

  it('Agent delete backend conflict remains visible', async () => {
    const fakeApi = api({
      deleteAgent: vi.fn(() => Promise.reject(new Error('AGENT_IN_USE: Agent is used by a workflow.')))
    });
    const { dom } = await openedProject(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-agent-id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteAgent).toHaveBeenCalledWith('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(dom.window.document.getElementById('agentsV2AgentsError')?.textContent).toContain('AGENT_IN_USE');
  });

  it('Workflow delete cancel makes no API call', async () => {
    const { dom, fakeApi } = await openedProject();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => false), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-workflow-id="33333333-3333-4333-8333-333333333333"]')?.click();
    await flushAsync();

    expect(dom.window.confirm).toHaveBeenCalledWith('Delete workflow "Full Testing"?');
    expect(fakeApi.deleteWorkflow).not.toHaveBeenCalled();
  });

  it('Workflow delete accepts confirmation, calls delete API, and refreshes Workflows and Tasks', async () => {
    const fakeApi = api({
      listProjectWorkflows: vi.fn()
        .mockResolvedValueOnce([workflow()])
        .mockResolvedValueOnce([{ ...workflow('44444444-4444-4444-8444-444444444444', [], project().id), name: 'Review Flow' }]),
      listProjectTasks: vi.fn((projectId: string, page = 0, size = 20) => Promise.resolve(taskPage([
        { ...task('task-1', 'SUCCEEDED', projectId), title: `Tasks refreshed page ${page}` }
      ], page, size)))
    });
    const { dom } = await openedProject(fakeApi);
    fakeApi.listProjectTasks.mockClear();
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-workflow-id="33333333-3333-4333-8333-333333333333"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteWorkflow).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333');
    expect(fakeApi.listProjectWorkflows).toHaveBeenCalledTimes(2);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledWith(project().id, 0, 20);
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Review Flow');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).not.toContain('Full Testing');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Tasks refreshed page 0');
  });

  it('Workflow delete backend conflict remains visible', async () => {
    const fakeApi = api({
      deleteWorkflow: vi.fn(() => Promise.reject(new Error('WORKFLOW_IN_USE: Workflow is used by a task.')))
    });
    const { dom } = await openedProject(fakeApi);
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-delete-workflow-id="33333333-3333-4333-8333-333333333333"]')?.click();
    await flushAsync();

    expect(fakeApi.deleteWorkflow).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333');
    expect(dom.window.document.getElementById('agentsV2WorkflowsError')?.textContent).toContain('WORKFLOW_IN_USE');
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
      outputSchema: {
        type: 'object',
        properties: {
          result: { type: 'string' }
        },
        required: ['result'],
        additionalProperties: false
      },
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

  it('Agent Output JSON Schema starts with a valid editable template', async () => {
    const { dom, page } = await openedProject();
    await page.openAgentModal();

    const output = dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement;
    expect(dom.window.document.querySelector('label[for="agentsV2AgentOutputJson"]')?.textContent).toBe('Output JSON Schema');
    expect(dom.window.document.querySelector('.field-hint')?.textContent).toContain('{"summ":"int"}');
    expect(JSON.parse(output.value)).toEqual({
      type: 'object',
      properties: {
        result: { type: 'string' }
      },
      required: ['result'],
      additionalProperties: false
    });

    output.value = '{"type":"object","properties":{"summ":{"type":"integer"}}}';
    dom.window.document.getElementById('agentsV2AgentOutputTemplate')?.click();
    expect(JSON.parse(output.value)).toEqual({
      type: 'object',
      properties: {
        result: { type: 'string' }
      },
      required: ['result'],
      additionalProperties: false
    });
  });

  it('formats valid Agent Output JSON Schema locally', async () => {
    const { dom, page } = await openedProject();
    await page.openAgentModal();

    const output = dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement;
    output.value = '{"type":"object","properties":{"summ":{"type":"integer"}},"required":["summ"],"additionalProperties":false}';
    dom.window.document.getElementById('agentsV2AgentOutputFormat')?.click();

    expect(output.value).toContain('\n  "type": "object"');
    expect(output.value).toContain('"summ": {');
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toBe('');
  });

  it('rejects malformed non-object and shorthand Agent Output JSON Schema without API calls', async () => {
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
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toBe('Output schema must be a JSON Schema object.');

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '{"summ":"int"}';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toContain('top-level "type" key');

    (dom.window.document.getElementById('agentsV2AgentOutputJson') as HTMLTextAreaElement).value = '{"type":"object","properties":{"summ":"int"}}';
    dom.window.document.getElementById('agentsV2AgentForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();
    expect(fakeApi.createAgent).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2AgentJsonError')?.textContent).toContain('Property "summ" must be a JSON Schema object');
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
    expect(dom.window.document.getElementById('agentsV2NodeEditorDialog')?.hasAttribute('open')).toBe(false);
  });

  it('compact Node keeps the small layout and renders configured ports as labels only', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-a', name: 'Review feedback', description: 'Feedback.', order: 0 },
        { id: 'input-b', name: 'Context', description: 'Context.', order: 1 },
        { id: 'input-c', name: 'Test result', description: 'Test.', order: 2 },
        { id: 'input-d', name: 'Extra notes', description: 'Notes.', order: 3 }
      ], [
        { id: 'output-a', name: 'Approved', description: 'Continue.', order: 0 },
        { id: 'output-b', name: 'Return', description: 'Return.', order: 1 },
        { id: 'output-c', name: 'Reject', description: 'Reject.', order: 2 }
      ]),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', [], 260, 20)
    ]))) });
    const { dom } = await openedBuilder(fakeApi);
    const source = consoleSourceText();
    const nodeElement = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-1"]')!;
    const noPortNode = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-2"]')!;

    expect(source).toContain('const NODE_WIDTH = 204;');
    expect(source).toMatch(/\.workflow-node\s*\{[\s\S]*width: 180px;/);
    expect(source).toMatch(/\.workflow-node\s*\{[\s\S]*min-height: max\(104px, calc\(var\(--workflow-node-port-rows, 1\) \* 17px \+ 24px\)\);/);
    expect(source).not.toMatch(/\.workflow-node-port-list\s*\{[^}]*max-height:/);
    expect(source).not.toMatch(/\.workflow-node-port-list\s*\{[^}]*overflow: hidden;/);
    expect(source).not.toContain('.workflow-node-port i');
    expect(source).not.toContain('<i aria-hidden="true"></i>');
    expect(nodeElement.getAttribute('style')).toContain('--workflow-node-port-rows:4');
    expect(noPortNode.getAttribute('style')).toContain('--workflow-node-port-rows:1');
    expect(portTexts(dom, 'node-1', 'input')).toEqual(['Review feedback', 'Context', 'Test result', 'Extra notes']);
    expect(portTexts(dom, 'node-1', 'output')).toEqual(['Approved', 'Return', 'Reject']);
    expect(portTexts(dom, 'node-2', 'input')).toEqual([]);
    expect(portTexts(dom, 'node-2', 'output')).toEqual([]);
    expect(nodeElement.querySelectorAll('[data-node-input]')).toHaveLength(1);
    expect(nodeElement.querySelectorAll('[data-node-output]')).toHaveLength(1);
    expect(nodeElement.querySelectorAll('.workflow-node-port button, .workflow-node-port .node-handle')).toHaveLength(0);
  });

  it('clicking compact Node opens Node Editor and Cancel leaves draft unchanged', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20, 'DEPENDENCIES_ONLY', [], [
        { id: 'out-1', name: 'Approved', description: 'Accepted.', order: 0 }
      ])
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(dom.window.document.querySelector('[data-node-input-mode]')).toBeNull();
    clickNode(dom, 'node-1');
    expect(dom.window.document.getElementById('agentsV2NodeEditorDialog')?.hasAttribute('open')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2NodeEditorAgent')?.textContent).toContain('Agent: Architect');
    expect(editorCompactRows(dom, 'outputs')).toHaveLength(1);
    expect(dom.window.document.getElementById('agentsV2NodeEditorBody')?.textContent).toContain('Accepted.');
    expect(dom.window.document.querySelector('[data-node-editor-port-name]')).toBeNull();
    editPort(dom, 'outputs', 'out-1');
    expect(editorEditingRows(dom, 'outputs')).toHaveLength(1);
    const name = dom.window.document.querySelector<HTMLInputElement>('[data-node-editor-port-direction="outputs"] [data-node-editor-port-name]')!;
    name.value = 'Ready';
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    expect(page.workflowBuilder.workflow.nodes[0].outputs[0].name).toBe('Approved');
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')?.textContent).toContain('Approved');
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')?.textContent).not.toContain('Ready');
  });

  it('Node Editor adds, deletes, validates, saves ports, and keeps renamed Port IDs', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-existing', name: 'Context', description: 'Existing context.', order: 0 }
      ], [
        { id: 'output-existing', name: 'Approved', description: 'Accepted.', order: 0 }
      ])
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    setRandomUuids(dom, ['input-added', 'output-added']);

    clickNode(dom, 'node-1');
    expect(editorCompactRows(dom, 'inputs')).toHaveLength(1);
    expect(editorCompactRows(dom, 'outputs')).toHaveLength(1);
    expect(editorEditingRows(dom, 'inputs')).toHaveLength(0);
    expect(editorEditingRows(dom, 'outputs')).toHaveLength(0);
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="inputs"]')?.click();
    expect(editorRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-existing', 'input-added']);
    expect(editorEditingRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-added']);
    setPortFields(editorRow(dom, 'inputs', 1), 'Review feedback', 'Feedback from review.');

    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="outputs"]')?.click();
    expect(editorRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-existing', 'output-added']);
    expect(editorEditingRows(dom, 'inputs')).toHaveLength(0);
    expect(editorEditingRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-added']);
    expect(editorCompactRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-existing', 'input-added']);
    expect(dom.window.document.getElementById('agentsV2NodeEditorBody')?.textContent).toContain('Feedback from review.');
    setPortFields(editorRow(dom, 'outputs', 1), 'Return', 'Return for changes.');

    editPort(dom, 'outputs', 'output-existing');
    expect(editorEditingRows(dom, 'inputs')).toHaveLength(0);
    expect(editorEditingRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-existing']);
    expect(editorCompactRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toContain('output-added');
    expect(dom.window.document.getElementById('agentsV2NodeEditorBody')?.textContent).toContain('Return for changes.');
    setPortFields(editorRow(dom, 'outputs', 0), 'Ready', 'Ready for testing.');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="input-existing"]')?.click();
    expect(editorRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-added']);
    expect(editorRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-existing', 'output-added']);
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();

    expect(page.workflowBuilder.workflow.nodes[0].inputs).toEqual([
      { id: 'input-added', name: 'Review feedback', description: 'Feedback from review.', order: 0 }
    ]);
    expect(page.workflowBuilder.workflow.nodes[0].outputs).toEqual([
      { id: 'output-existing', name: 'Ready', description: 'Ready for testing.', order: 0 },
      { id: 'output-added', name: 'Return', description: 'Return for changes.', order: 1 }
    ]);
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')?.textContent).toContain('Review feedback');
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')?.textContent).toContain('Ready');
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')?.textContent).toContain('Return');

    clickNode(dom, 'node-1');
    expect(editorCompactRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-added']);
    expect(editorCompactRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-existing', 'output-added']);
    expect(editorEditingRows(dom, 'inputs')).toHaveLength(0);
    expect(editorEditingRows(dom, 'outputs')).toHaveLength(0);
  });

  it('Node Editor switches and clears the single active port editor without losing draft values', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 10, 20, 'DEPENDENCIES_ONLY', [], [
        { id: 'output-a', name: 'Approved', description: 'Accepted.', order: 0 },
        { id: 'output-b', name: 'Return', description: 'Return for changes.', order: 1 }
      ])
    ]))) });
    const { dom } = await openedBuilder(fakeApi);

    clickNode(dom, 'node-1');
    expect(editorEditingRows(dom, 'outputs')).toHaveLength(0);
    editPort(dom, 'outputs', 'output-a');
    expect(editorEditingRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-a']);
    setPortFields(editorRow(dom, 'outputs', 0), 'Ready', 'Ready for testing.');
    editPort(dom, 'outputs', 'output-b');
    expect(editorEditingRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-b']);
    expect(editorCompactRows(dom, 'outputs').map((row) => row.textContent)).toEqual([
      expect.stringContaining('Ready')
    ]);
    const activeOutputRow = editorEditingRows(dom, 'outputs')[0];
    expect(activeOutputRow).toBeDefined();
    expect(activeOutputRow!.textContent).toContain('Return');

    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="output-b"]')?.click();
    expect(editorEditingRows(dom, 'outputs')).toHaveLength(0);
    expect(editorCompactRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-a']);
    expect(dom.window.document.getElementById('agentsV2NodeEditorBody')?.textContent).toContain('Ready for testing.');
  });

  it('Node Editor rejects invalid names and descriptions while allowing same name across directions', async () => {
    const { dom, page } = await openedBuilder(api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    ]))) }));
    setRandomUuids(dom, ['input-a', 'input-b', 'output-a', 'output-b']);

    clickNode(dom, 'node-1');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="inputs"]')?.click();
    setPortFields(editorRow(dom, 'inputs', 0), 'Result', 'Input result.');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="inputs"]')?.click();
    setPortFields(editorRow(dom, 'inputs', 1), 'Result', 'Duplicate input.');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="outputs"]')?.click();
    setPortFields(editorRow(dom, 'outputs', 0), 'Result', 'Output result.');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-add="outputs"]')?.click();
    setPortFields(editorRow(dom, 'outputs', 1), 'Return', ' ');

    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent).toContain('Input port names must be unique');
    expect(page.workflowBuilder.workflow.nodes[0].inputs).toEqual([]);

    editPort(dom, 'inputs', 'input-b');
    setPortFields(editorRow(dom, 'inputs', 1), 'Context', 'Context input.');
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent).toContain('Output port description is required');

    editPort(dom, 'outputs', 'output-b');
    setPortFields(editorRow(dom, 'outputs', 1), 'Return', 'Return for changes.');
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();
    expect(page.workflowBuilder.workflow.nodes[0].inputs.map((port: any) => port.name)).toEqual(['Result', 'Context']);
    expect(page.workflowBuilder.workflow.nodes[0].outputs.map((port: any) => port.name)).toEqual(['Result', 'Return']);
  });

  it('compact Node renders ports by order and Workflow Save payload contains inputs and outputs', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
        node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', [], 44, 55, 'DEPENDENCIES_ONLY', [
          { id: 'input-b', name: 'Second input', description: 'Second.', order: 1 },
          { id: 'input-a', name: 'First input', description: 'First.', order: 0 }
        ], [
          { id: 'output-b', name: 'Second output', description: 'Second.', order: 1 },
          { id: 'output-a', name: 'First output', description: 'First.', order: 0 }
        ])
      ]))),
      updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes)))
    });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(portTexts(dom, 'node-1', 'input')).toEqual(['First input', 'Second input']);
    expect(portTexts(dom, 'node-1', 'output')).toEqual(['First output', 'Second output']);
    await page.workflowBuilder.save();

    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', {
      name: 'Full Testing',
      nodes: [{
        id: 'node-1',
        targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        dependsOnNodeIds: [],
        inputMode: 'DEPENDENCIES_ONLY',
        inputs: [
          { id: 'input-a', name: 'First input', description: 'First.', order: 0 },
          { id: 'input-b', name: 'Second input', description: 'Second.', order: 1 }
        ],
        outputs: [
          { id: 'output-a', name: 'First output', description: 'First.', order: 0 },
          { id: 'output-b', name: 'Second output', description: 'Second.', order: 1 }
        ],
        position: { x: 44, y: 55 }
      }]
    });
    expect(portTexts(dom, 'node-1', 'input')).toEqual(['First input', 'Second input']);
    expect(portTexts(dom, 'node-1', 'output')).toEqual(['First output', 'Second output']);
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
        inputMode: 'DEPENDENCIES_ONLY',
        inputs: [],
        outputs: [],
        position: { x: 80, y: 90 }
      }]
    });
    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 80, y: 90 });

    await page.workflowBuilder.save();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('WORKFLOW_GRAPH_CYCLE');
  });

  it('Workflow Builder edits dependency input mode in the Node Editor', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', ['node-1'], 260, 20)
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(dom.window.document.querySelector('[data-node-input-mode]')).toBeNull();
    clickNode(dom, 'node-1');
    expect(dom.window.document.querySelector('[data-node-editor-root-input]')?.textContent).toContain('Original task');
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    clickNode(dom, 'node-2');
    const dependentSelect = dom.window.document.querySelector<HTMLSelectElement>('[data-node-editor-input-mode]')!;
    expect([...dependentSelect.options].map((option) => option.textContent)).toEqual(['Dependencies only', 'Task + dependencies']);
    dependentSelect.value = 'TASK_AND_DEPENDENCIES';
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();
    await page.workflowBuilder.save();

    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', {
      name: 'Full Testing',
      nodes: [
        {
          id: 'node-1',
          targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          dependsOnNodeIds: [],
          inputMode: 'DEPENDENCIES_ONLY',
          inputs: [],
          outputs: [],
          position: { x: 10, y: 20 }
        },
        {
          id: 'node-2',
          targetId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          dependsOnNodeIds: ['node-1'],
          inputMode: 'TASK_AND_DEPENDENCIES',
          inputs: [],
          outputs: [],
          position: { x: 260, y: 20 }
        }
      ]
    });
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
    const http = { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
    const client = createAgentProjectsApi(http);
    client.listProjects();
    client.createProject({ name: 'Sitionix' });
    client.deleteProject(project().id);
    client.listProjectAgents(project().id);
    client.createAgent(project().id, { name: 'Agent', instructions: 'Do work.', outputSchema: {} });
    client.getAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    client.updateAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', { name: 'Agent', instructions: 'Do work.', outputSchema: {} });
    client.deleteAgent('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    client.listProjectWorkflows(project().id);
    client.createWorkflow(project().id, { name: 'Full Testing' });
    client.getWorkflow('33333333-3333-4333-8333-333333333333');
    client.updateWorkflow('33333333-3333-4333-8333-333333333333', { name: 'Full Testing', nodes: [] });
    client.deleteWorkflow('33333333-3333-4333-8333-333333333333');
    client.listProjectTasks(project().id);
    client.createProjectTask(project().id, { title: 'Check calculation', input: 'Count letters.', workflowId: '33333333-3333-4333-8333-333333333333' });
    client.getProjectTask('55555555-5555-4555-8555-555555555555');
    client.deleteProjectTask('55555555-5555-4555-8555-555555555555');
    client.getWorkflowRun('66666666-6666-4666-8666-666666666666');

    const calls = [...http.get.mock.calls, ...http.post.mock.calls, ...http.put.mock.calls, ...http.delete.mock.calls].map(([path]) => path);
    expect(calls.every((path) => path.startsWith('/agents'))).toBe(true);
    expect(http.get).toHaveBeenCalledWith(`/agents/projects/${project().id}/tasks?page=0&size=20`);
    expect(http.post).toHaveBeenCalledWith(`/agents/projects/${project().id}/tasks`, {
      title: 'Check calculation',
      input: 'Count letters.',
      workflowId: '33333333-3333-4333-8333-333333333333'
    });
    expect(http.get).toHaveBeenCalledWith('/agents/tasks/55555555-5555-4555-8555-555555555555');
    expect(http.delete).toHaveBeenCalledWith(`/agents/projects/${project().id}`);
    expect(http.delete).toHaveBeenCalledWith('/agents/definitions/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(http.delete).toHaveBeenCalledWith('/agents/workflows/33333333-3333-4333-8333-333333333333');
    expect(http.delete).toHaveBeenCalledWith('/agents/tasks/55555555-5555-4555-8555-555555555555');
    expect(http.get).toHaveBeenCalledWith('/agents/workflow-runs/66666666-6666-4666-8666-666666666666');
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

function clickNode(dom: JSDOM, nodeId: string) {
  const element = dom.window.document.querySelector<HTMLElement>(`[data-node-id="${nodeId}"]`)!;
  element.dispatchEvent(pointer(dom, 'pointerdown', 10, 20));
  dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 10, 20));
}

function editorRows(dom: JSDOM, direction: string) {
  return [...dom.window.document.querySelectorAll<HTMLElement>(`[data-node-editor-port-direction="${direction}"]`)];
}

function editorCompactRows(dom: JSDOM, direction: string) {
  return [...dom.window.document.querySelectorAll<HTMLElement>(`.node-editor-port-row.compact[data-node-editor-port-direction="${direction}"]`)];
}

function editorEditingRows(dom: JSDOM, direction: string) {
  return [...dom.window.document.querySelectorAll<HTMLElement>(`.node-editor-port-row.editing[data-node-editor-port-direction="${direction}"]`)];
}

function editorRow(dom: JSDOM, direction: string, index: number) {
  const row = editorRows(dom, direction)[index];
  expect(row).toBeDefined();
  return row!;
}

function editPort(dom: JSDOM, direction: string, portId: string) {
  dom.window.document.querySelector<HTMLElement>(`button[data-node-editor-edit="${portId}"][data-node-editor-edit-direction="${direction}"]`)?.click();
}

function setPortFields(row: HTMLElement, name: string, description: string) {
  row.querySelector<HTMLInputElement>('[data-node-editor-port-name]')!.value = name;
  row.querySelector<HTMLTextAreaElement>('[data-node-editor-port-description]')!.value = description;
}

function portTexts(dom: JSDOM, nodeId: string, direction: string) {
  return [...dom.window.document.querySelectorAll(`[data-node-id="${nodeId}"] .workflow-node-port-list.${direction} .workflow-node-port span`)]
    .map((element) => element.textContent);
}

function consoleSourceText() {
  const root = join(process.cwd(), 'src', 'operator');
  return walk(root)
    .filter((file) => file.endsWith('.js') || file.endsWith('.html') || file.endsWith('.css'))
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
