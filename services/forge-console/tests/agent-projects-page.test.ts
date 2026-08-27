import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { JSDOM } from 'jsdom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AgentProjectsPage } from '../src/operator/agent-projects-page.js';
import { createAgentProjectsApi } from '../src/operator/agent-projects-api.js';
import { bootstrapOperatorConsole } from '../src/operator/operator-bootstrap.js';
import { effortTone } from '../src/operator/project-workspace.js';
import { executionConnectionsMayBundle, routesSharePositiveLengthSegment } from '../src/operator/task-execution-view.js';

const MODERN_EXECUTION_CARD_WIDTH = 288;

function agentProjectsDom(url = 'http://127.0.0.1/fgaisox/operator/agent-projects.html') {
  return new JSDOM(readFileSync(join(process.cwd(), 'src', 'operator', 'agent-projects.html'), 'utf8'), {
    url,
    pretendToBeVisual: true
  });
}

async function flushAsync() {
  for (let index = 0; index < 14; index += 1) {
    await Promise.resolve();
  }
}

async function goBack(dom: JSDOM) {
  const navigated = new Promise<void>((resolve) =>
    dom.window.addEventListener('popstate', () => resolve(), { once: true })
  );
  dom.window.history.back();
  await navigated;
  await flushAsync();
}

async function goForward(dom: JSDOM) {
  const navigated = new Promise<void>((resolve) =>
    dom.window.addEventListener('popstate', () => resolve(), { once: true })
  );
  dom.window.history.forward();
  await navigated;
  await flushAsync();
}

function taskRepositoryOption(document: Document, index = 0) {
  const option = document.querySelectorAll<HTMLInputElement>('#agentsV2TaskRepositories input').item(index);
  if (!option) {
    throw new Error(`Task repository option ${index} was not rendered.`);
  }
  return option;
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

function workflow(id = '33333333-3333-4333-8333-333333333333', nodes: any[] = [], projectId = project().id, connections: any[] | null = null, taskInputPortId: string | null = null, taskOutputPortId: string | null = null) {
  return {
    id,
    projectId,
    name: 'Full Testing',
    nodes: nodes.map((item) => ({ scopeMode: 'GLOBAL', ...item })),
    connections: connections || [],
    taskInputPortId,
    taskOutputPortId,
    createdAt: '2026-08-04T00:00:00Z',
    updatedAt: '2026-08-04T00:00:00Z'
  };
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

function repository(
  id = '88888888-8888-4888-8888-888888888888',
  projectId = project().id,
  name = 'service-a',
  cloned = false,
  git: any = cloned ? repositoryGitState() : null
) {
  return {
    id,
    projectId,
    name,
    remoteUrl: name === 'service-b' ? 'https://github.com/company/service-b.git' : 'git@gitlab.com:company/service-a.git',
    cloned,
    git,
    createdAt: '2026-08-17T09:00:00Z'
  };
}

function service(id = '99999999-9999-4999-8999-999999999999', projectId = project().id) {
  return {
    id,
    projectId,
    name: 'api',
    repositoryId: null,
    runtimeTarget: {
      connection: 'LOCAL',
      sshConnectionId: null,
      provider: 'DOCKER',
      container: 'api',
      unit: null
    }
  };
}

function repositoryGitState(branch: string | null = 'main', workingTree: string | null = 'CLEAN', pullAvailable = false) {
  return {
    branch,
    workingTree,
    pullAvailable
  };
}

function branchGitState(workingTree = 'CLEAN', ref = 'main', pullAvailable = false) {
  return repositoryGitState(ref, workingTree, pullAvailable);
}

function behindGitState(ref = 'main') {
  return branchGitState('CLEAN', ref, true);
}

function detachedGitState(workingTree = 'CLEAN') {
  return repositoryGitState(null, workingTree, false);
}

function conflictedGitState() {
  return branchGitState('DIRTY', 'main', false);
}

function noUpstreamGitState() {
  return branchGitState('CLEAN', 'main', false);
}

function divergedGitState() {
  return branchGitState('CLEAN', 'main', false);
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
  upstreamNodeRunIds: string[] = [],
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
    inputMode: 'DEPENDENCIES_ONLY',
    position: { x, y },
    executionFrameId: 'frame-root',
    enteredViaInputPortId: upstreamNodeRunIds.length > 0 ? `${id}-input` : null,
    activationFrameId: upstreamNodeRunIds.length > 0 ? 'frame-root' : null,
    selectedOutputPortId: null,
    testUpstreamNodeRunIds: upstreamNodeRunIds,
    status,
    output,
    failure,
    createdAt: '2026-08-13T10:00:00Z',
    startedAt: status === 'PENDING' || status === 'BLOCKED' ? null : '2026-08-13T10:01:00Z',
    finishedAt: status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED' ? '2026-08-13T10:02:00Z' : null
  };
}

function workflowRunDetail(id: string, status: string, nodeRuns: any[] = [], workflowName = 'Full Testing', runtimeGraph: any = null) {
  const connectionResolutions = nodeRuns.flatMap((target) => (target.testUpstreamNodeRunIds || []).map((sourceId: string, index: number) => ({
    id: `${sourceId}-${target.id}-resolution-${index}`,
    executionFrameId: target.activationFrameId || target.executionFrameId,
    sourceNodeRunId: sourceId,
    sourceConnectionId: `${sourceId}-${target.id}-connection`,
    targetInputPortId: target.enteredViaInputPortId || `${target.id}-input`,
    resolutionType: 'DELIVERED',
    payload: { value: `Output from ${sourceId}` },
    consumedByNodeRunId: target.id,
    createdAt: target.createdAt
  })));
  return {
    id,
    taskId: task().id,
    workflowName,
    status,
    nodeRuns,
    connectionResolutions,
    runtimeGraph,
    createdAt: '2026-08-13T10:00:00Z',
    startedAt: '2026-08-13T10:00:02Z',
    finishedAt: status === 'RUNNING' || status === 'QUEUED' ? null : '2026-08-13T10:03:00Z'
  };
}

function runtimeGraph(nodes: any[], connections: any[] = [], taskInputPortId: string | null = null, taskOutputPortId: string | null = null) {
  return {
    taskInputPortId,
    taskOutputPortId,
    nodes: nodes.map((item) => ({
      sourceNodeId: item.id,
      agentName: item.agentName,
      scopeMode: item.scopeMode || 'GLOBAL',
      position: item.position || { x: 10, y: 20 }
    })),
    ports: nodes.flatMap((item) => [
      ...(item.inputs || [{ id: `${item.id}-input`, name: 'Input', order: 0 }])
        .map((port: any) => ({ sourcePortId: port.id, sourceNodeId: item.id, direction: 'INPUT', name: port.name, order: port.order })),
      ...(item.outputs || [{ id: `${item.id}-output`, name: 'Output', order: 0 }])
        .map((port: any) => ({ sourcePortId: port.id, sourceNodeId: item.id, direction: 'OUTPUT', name: port.name, order: port.order }))
    ]),
    connections: connections.map((item) => ({
      sourceConnectionId: item.id,
      sourceOutputPortId: item.sourceOutputPortId,
      targetInputPortId: item.targetInputPortId
    }))
  };
}

function modernNodeRun(
  id: string,
  sourceNodeId: string,
  status: string,
  createdAt: string,
  selectedOutputPortId: string | null = null
) {
  return {
    ...nodeRun(id, sourceNodeId, status),
    sourceNodeId,
    agentName: sourceNodeId,
    selectedOutputPortId,
    createdAt,
    startedAt: status === 'PENDING' ? null : createdAt,
    finishedAt: status === 'RUNNING' || status === 'PENDING' ? null : createdAt
  };
}

function node(
  id: string,
  targetId: string,
  x = 10,
  y = 20,
  inputMode = 'DEPENDENCIES_ONLY',
  inputs: any[] = [],
  outputs: any[] = []
) {
  return { id, targetId, inputMode, scopeMode: 'GLOBAL', inputs, outputs, position: { x, y } };
}

function portedNode(
  id: string,
  targetId: string,
  x = 10,
  y = 20,
  inputMode = 'DEPENDENCIES_ONLY'
) {
  return node(
    id,
    targetId,
    x,
    y,
    inputMode,
    [{ id: `${id}-input`, name: 'Input', description: 'Default workflow input.', order: 0 }],
    [{ id: `${id}-output`, name: 'Output', description: 'Default workflow output.', order: 0 }]
  );
}

function connection(sourceNodeId: string, targetNodeId: string, id = `${sourceNodeId}-${targetNodeId}-connection`) {
  return {
    id,
    sourceOutputPortId: `${sourceNodeId}-output`,
    targetInputPortId: `${targetNodeId}-input`
  };
}

function portConnection(id: string, sourceOutputPortId: string, targetInputPortId: string) {
  return { id, sourceOutputPortId, targetInputPortId };
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
    listServices: vi.fn(() => Promise.resolve([])),
    createService: vi.fn((_projectId: string, request: any) => Promise.resolve({ ...service(), ...request })),
    updateService: vi.fn((_projectId: string, id: string, request: any) => Promise.resolve({ ...service(id), ...request })),
    deleteService: vi.fn(() => Promise.resolve({})),
    getService: vi.fn((_projectId: string, id: string) => Promise.resolve(service(id))),
    getServiceRuntime: vi.fn(() => Promise.resolve({ status: 'RUNNING', connection: 'LOCAL', provider: 'DOCKER', targetIdentity: 'api', metadata: {} })),
    listServiceLogSources: vi.fn(() => Promise.resolve([])),
    discoverRuntimeTargets: vi.fn(() => Promise.resolve([])),
    listLogSources: vi.fn(() => Promise.resolve([])),
    listSshConnections: vi.fn(() => Promise.resolve([])),
    listProjectRepositories: vi.fn((projectId: string) => Promise.resolve([repository(undefined, projectId)])),
    importProjectRepository: vi.fn(() => Promise.resolve(repository())),
    cloneProjectRepository: vi.fn(() => Promise.resolve(repository(undefined, project().id, 'service-a', true))),
    refreshProjectRepository: vi.fn(() => Promise.resolve(repository(undefined, project().id, 'service-a', true))),
    pullProjectRepository: vi.fn(() => Promise.resolve(repository(undefined, project().id, 'service-a', true))),
    getRuntime: vi.fn(() => Promise.resolve(runtime())),
    listProjectAgents: vi.fn(() => Promise.resolve(agents)),
    createAgent: vi.fn(() => Promise.resolve(agent('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'Analyzer'))),
    getAgent: vi.fn((agentId: string) => Promise.resolve(agents.find((item) => item.id === agentId) || agents[0])),
    updateAgent: vi.fn(() => Promise.resolve(agents[0])),
    deleteAgent: vi.fn(() => Promise.resolve({})),
    listProjectWorkflows: vi.fn(() => Promise.resolve([workflow()])),
    createWorkflow: vi.fn(() => Promise.resolve(workflow('44444444-4444-4444-8444-444444444444'))),
    getWorkflow: vi.fn((workflowId: string) => Promise.resolve(workflow(workflowId))),
    updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes, project().id, request.connections, request.taskInputPortId, request.taskOutputPortId))),
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

async function openedRepository(fakeApi = api(), repositoryId = repository().id) {
  const context = await openedProject(fakeApi);
  await context.page.openRepositoryWorkspace(project().id, repositoryId);
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

function stubRect(element: Element | null, left: number, top: number, width: number, height: number) {
  if (!element) {
    throw new Error('Expected element to exist');
  }
  element.getBoundingClientRect = () => ({
    left,
    top,
    right: left + width,
    bottom: top + height,
    x: left,
    y: top,
    width,
    height,
    toJSON: () => ({})
  });
}

function stubAnchor(dom: JSDOM, portId: string, centerX: number, centerY: number) {
  stubRect(dom.window.document.querySelector(`[data-runtime-port-anchor-id="${portId}"]`), centerX - 4, centerY - 4, 8, 8);
}

function pathPoints(path: string) {
  const points: Array<{ x: number; y: number }> = [];
  let current = { x: 0, y: 0 };
  for (const match of path.matchAll(/([MHVQ])((?:\s-?\d+(?:\.\d+)?)+)/g)) {
    const command = match[1]!;
    const numbers = (match[2]!.match(/-?\d+(?:\.\d+)?/g) || []).map(Number);
    if (command === 'M') {
      current = { x: numbers[0]!, y: numbers[1]! };
      points.push(current);
    }
    if (command === 'H') {
      current = { x: numbers[0]!, y: current.y };
      points.push(current);
    }
    if (command === 'V') {
      current = { x: current.x, y: numbers[0]! };
      points.push(current);
    }
    if (command === 'Q') {
      points.push({ x: numbers[0]!, y: numbers[1]! });
      current = { x: numbers[2]!, y: numbers[3]! };
      points.push(current);
    }
  }
  return points;
}

function expectPathOutsideRects(path: string, rects: Array<{ left: number; top: number; right: number; bottom: number }>) {
  for (const point of pathPoints(path)) {
    for (const rect of rects) {
      expect(point.x > rect.left && point.x < rect.right && point.y > rect.top && point.y < rect.bottom).toBe(false);
    }
  }
}

function pointer(dom: JSDOM, type: string, x: number, y: number) {
  return new dom.window.MouseEvent(type, { clientX: x, clientY: y, bubbles: true, cancelable: true });
}

function wheel(dom: JSDOM, x: number, y: number, deltaY: number) {
  return new dom.window.WheelEvent('wheel', { clientX: x, clientY: y, deltaY, bubbles: true, cancelable: true });
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
  it('creates a Service through the typed form without prompts', async () => {
    const createService = vi.fn().mockResolvedValue(service());
    const fakeApi = api({ createService, listSshConnections: vi.fn().mockResolvedValue([]) });
    const { dom, page } = await openedProject(fakeApi);
    const prompt = vi.spyOn(dom.window, 'prompt');

    await page.openServiceModal();
    (dom.window.document.getElementById('projectServiceName') as HTMLInputElement).value = 'worker';
    (dom.window.document.getElementById('projectServiceRepository') as HTMLSelectElement).value =
      '88888888-8888-4888-8888-888888888888';
    (dom.window.document.getElementById('projectServiceProvider') as HTMLSelectElement).value = 'SYSTEMD';
    (dom.window.document.getElementById('projectServiceUnit') as HTMLInputElement).value = 'worker.service';
    page.renderServiceTargetFields();
    await page.submitService(new dom.window.Event('submit'));

    expect(prompt).not.toHaveBeenCalled();
    expect(createService).toHaveBeenCalledWith(project().id, {
      name: 'worker',
      repositoryId: '88888888-8888-4888-8888-888888888888',
      runtimeTarget: {
        connection: 'LOCAL', sshConnectionId: null, provider: 'SYSTEMD',
        container: null, unit: 'worker.service'
      }
    });
    page.dispose();
  });

  it('edits SSH Services and deletes them through CRUD APIs', async () => {
    const existing = {
      ...service(),
      runtimeTarget: {
        connection: 'SSH', sshConnectionId: 'ssh-1', provider: 'DOCKER',
        container: 'api', unit: null
      }
    };
    const updateService = vi.fn().mockResolvedValue(existing);
    const deleteService = vi.fn().mockResolvedValue({});
    const fakeApi = api({
      listServices: vi.fn().mockResolvedValue([existing]),
      listSshConnections: vi.fn().mockResolvedValue([
        { id: 'ssh-1', name: 'prod', username: 'operator', host: 'prod.local' }
      ]),
      updateService,
      deleteService
    });
    const { dom, page } = await openedProject(fakeApi);
    dom.window.confirm = vi.fn(() => true);

    await page.openServiceModal(existing.id);
    expect((dom.window.document.getElementById('projectServiceSsh') as HTMLSelectElement).value)
      .toBe('ssh-1');
    (dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).value = 'api-v2';
    await page.submitService(new dom.window.Event('submit'));
    expect(updateService).toHaveBeenCalledWith(
      project().id, existing.id,
      expect.objectContaining({ runtimeTarget: expect.objectContaining({ sshConnectionId: 'ssh-1', container: 'api-v2' }) })
    );

    await page.deleteService(existing.id);
    expect(deleteService).toHaveBeenCalledWith(project().id, existing.id);
    page.dispose();
  });

  it('renders repository cards with Git state and asynchronous runtime summaries', async () => {
    let resolveRuntime: (value: any) => void = () => {};
    const pendingRuntime = new Promise((resolve) => { resolveRuntime = resolve; });
    const firstRepository = repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'));
    const secondRepository = repository('repo-2', project().id, 'worker', true, branchGitState('DIRTY', 'develop'));
    const first = { ...service('service-1'), repositoryId: firstRepository.id };
    const second = { ...service('service-2'), repositoryId: secondRepository.id };
    const getServiceRuntime = vi.fn((_projectId: string, serviceId: string) =>
      serviceId === first.id ? pendingRuntime : Promise.reject(new Error('unavailable')));
    const { dom, page } = await openedProject(api({
      listProjectRepositories: vi.fn().mockResolvedValue([firstRepository, secondRepository]),
      listServices: vi.fn().mockResolvedValue([first, second]), getServiceRuntime
    }));

    const statuses = () => [...dom.window.document.querySelectorAll('[data-repository-runtime-status]')]
      .map((element) => element.textContent?.trim());
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('main · Clean');
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('develop · Dirty');
    expect(statuses()).toEqual(['UNKNOWN', 'UNKNOWN']);
    resolveRuntime({ status: 'RUNNING' });
    await flushAsync();
    expect(statuses()).toEqual(['RUNNING', 'UNKNOWN']);
    page.dispose();
  });

  it('ignores stale Service runtime responses after switching Projects', async () => {
    const projectOne = project();
    const projectTwo = project('22222222-2222-4222-8222-222222222222', 'Second');
    let resolveOldRuntime: (value: any) => void = () => {};
    const oldRuntime = new Promise((resolve) => { resolveOldRuntime = resolve; });
    const fakeApi = api({
      listProjects: vi.fn().mockResolvedValue([projectOne, projectTwo]),
      listServices: vi.fn((projectId: string) => Promise.resolve([
        { ...service(), projectId, repositoryId: repository().id, name: projectId === projectOne.id ? 'Old' : 'Current' }
      ])),
      getServiceRuntime: vi.fn((projectId: string) =>
        projectId === projectOne.id ? oldRuntime : Promise.resolve({ status: 'STOPPED' }))
    });
    const { dom, page } = await mountedPage(fakeApi);

    await page.openProject(projectOne.id);
    await page.openProject(projectTwo.id);
    await flushAsync();
    resolveOldRuntime({ status: 'RUNNING' });
    await flushAsync();

    expect(dom.window.document.querySelector('[data-repository-runtime-status]')?.textContent)
      .toContain('STOPPED');
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('service-a');
    page.dispose();
  });

  it('ignores an older runtime response after the same Service target is reloaded', async () => {
    const targetA = { ...service(), repositoryId: repository().id };
    const targetB = {
      ...targetA,
      runtimeTarget: { ...targetA.runtimeTarget, container: 'api-v2' }
    };
    let resolveA: (value: any) => void = () => {};
    let resolveB: (value: any) => void = () => {};
    const runtimeA = new Promise((resolve) => { resolveA = resolve; });
    const runtimeB = new Promise((resolve) => { resolveB = resolve; });
    const listServices = vi.fn()
      .mockResolvedValueOnce([targetA])
      .mockResolvedValueOnce([targetB]);
    const getServiceRuntime = vi.fn()
      .mockReturnValueOnce(runtimeA)
      .mockReturnValueOnce(runtimeB);
    const { dom, page } = await openedProject(api({ listServices, getServiceRuntime }));

    await page.loadServices(project().id, page.projectLoadSequence);
    resolveB({ status: 'RUNNING' });
    await flushAsync();
    expect(dom.window.document.querySelector('[data-repository-runtime-status]')?.textContent)
      .toContain('RUNNING');

    resolveA({ status: 'FAILED' });
    await flushAsync();
    expect(dom.window.document.querySelector('[data-repository-runtime-status]')?.textContent)
      .toContain('RUNNING');
    page.dispose();
  });

  it('keeps Project lightweight and exposes only the dedicated Logs entry point', async () => {
    const listLogSources = vi.fn().mockResolvedValue([]);
    const streams: any[] = [];
    class EventSourceFake {
      constructor() {
        streams.push(this);
      }
      addEventListener() {}
      close() {}
    }
    const { dom, page } = await openedProject(api({ listLogSources }));
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });

    const projectWorkspace = dom.window.document.getElementById('agentsV2Workspace')!;
    expect(projectWorkspace.querySelector('#projectLogsOpen')?.textContent).toContain('Open Logs');
    expect(projectWorkspace.querySelector('#projectLogsOutput')).toBeNull();
    expect(projectWorkspace.querySelector('#projectLogsLive')).toBeNull();
    expect(listLogSources).not.toHaveBeenCalled();
    expect(streams).toHaveLength(0);
    page.dispose();
  });

  it('navigates to the dedicated Logs route and loads its configured sources', async () => {
    const source = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: 'Application',
      enabled: true,
      serviceId: null,
      connection: 'LOCAL',
      provider: 'DOCKER'
    };
    const listLogSources = vi.fn().mockResolvedValue([source]);
    const { dom, page } = await openedProject(api({
      listLogSources,
      listSshConnections: vi.fn().mockResolvedValue([])
    }));

    dom.window.document.getElementById('projectLogsOpen')!.click();
    await flushAsync();

    expect(dom.window.location.pathname).toBe('/fgaisox/operator/agent-projects.html');
    expect(dom.window.location.hash).toBe(`#/projects/${project().id}/logs`);
    expect(dom.window.document.getElementById('projectLogsWorkspace')!.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('projectLogsTitle')!.textContent).toBe('Sitionix Logs');
    expect(dom.window.document.getElementById('projectLogsSources')!.textContent).toContain('Application');
    expect(listLogSources).toHaveBeenCalledWith(project().id);
    page.dispose();
  });

  it('mounts the dedicated workspace when the current route is a Project Logs route', async () => {
    const dom = agentProjectsDom(
      `http://127.0.0.1/fgaisox/operator/agent-projects.html#/projects/${project().id}/logs`
    );
    const listLogSources = vi.fn().mockResolvedValue([]);
    const page = new AgentProjectsPage({
      document: dom.window.document,
      window: dom.window,
      api: api({ listLogSources, listSshConnections: vi.fn().mockResolvedValue([]) })
    });

    page.mount();
    await flushAsync();

    expect(dom.window.document.getElementById('projectLogsWorkspace')!.classList.contains('hidden')).toBe(false);
    expect(dom.window.document.getElementById('agentsV2Workspace')!.classList.contains('hidden')).toBe(true);
    expect(dom.window.location.pathname).toBe('/fgaisox/operator/agent-projects.html');
    expect(listLogSources).toHaveBeenCalledWith(project().id);
    page.dispose();
  });

  it('closes the dedicated Logs EventSource when returning to Project', async () => {
    const streams: Array<{ closed: boolean }> = [];
    class EventSourceFake {
      listeners = new Map<string, Function>();
      closed = false;
      constructor() {
        streams.push(this);
      }
      addEventListener(name: string, listener: Function) {
        this.listeners.set(name, listener);
      }
      close() {
        this.closed = true;
      }
    }
    const dom = agentProjectsDom();
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });
    const fakeApi = api({
      listLogSources: vi.fn().mockResolvedValue([{
        id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        name: 'Application',
        enabled: true,
        serviceId: null,
        connection: 'LOCAL',
        provider: 'DOCKER'
      }]),
      listSshConnections: vi.fn().mockResolvedValue([]),
      logStreamUrl: vi.fn().mockReturnValue('/stream')
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    dom.window.document.getElementById('projectLogsOpen')!.click();
    await flushAsync();
    dom.window.document.getElementById('projectLogsLive')!.click();
    expect(streams).toHaveLength(1);

    dom.window.document.getElementById('projectLogsBack')!.click();
    await flushAsync();

    expect(streams[0]!.closed).toBe(true);
    expect(dom.window.location.pathname).toBe('/fgaisox/operator/agent-projects.html');
    expect(dom.window.location.hash).toBe('');
    expect(dom.window.document.getElementById('projectLogsWorkspace')!.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Workspace')!.classList.contains('hidden')).toBe(false);
    page.dispose();
  });

  it('keeps the Logs workspace lifecycle correct across browser back and forward', async () => {
    const streams: Array<{ closed: boolean }> = [];
    class EventSourceFake {
      closed = false;
      constructor() {
        streams.push(this);
      }
      addEventListener() {}
      close() {
        this.closed = true;
      }
    }
    const listLogSources = vi.fn().mockResolvedValue([{
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: 'Application',
      enabled: true,
      serviceId: null,
      connection: 'LOCAL',
      provider: 'DOCKER'
    }]);
    const { dom, page } = await openedProject(api({
      listLogSources,
      listSshConnections: vi.fn().mockResolvedValue([]),
      logStreamUrl: vi.fn().mockReturnValue('/stream')
    }));
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });

    dom.window.document.getElementById('projectLogsOpen')!.click();
    await flushAsync();
    expect(dom.window.location.hash).toBe(`#/projects/${project().id}/logs`);
    dom.window.document.getElementById('projectLogsLive')!.click();
    expect(streams).toHaveLength(1);

    await goBack(dom);
    expect(dom.window.location.pathname).toBe('/fgaisox/operator/agent-projects.html');
    expect(dom.window.location.hash).toBe('');
    expect(dom.window.document.getElementById('projectLogsWorkspace')!.classList.contains('hidden')).toBe(true);
    expect(streams[0]!.closed).toBe(true);

    await goForward(dom);
    expect(dom.window.location.hash).toBe(`#/projects/${project().id}/logs`);
    expect(dom.window.document.getElementById('projectLogsWorkspace')!.classList.contains('hidden')).toBe(false);
    expect(listLogSources).toHaveBeenCalledTimes(2);
    page.dispose();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('navigation exposes typed Agent Projects without legacy runtime pages', () => {
    const dom = agentProjectsDom();
    bootstrapOperatorConsole({ document: dom.window.document, window: dom.window, http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } });
    expect(dom.window.document.querySelector('.sidebar-link.active')?.textContent).toContain('Projects');
    expect(dom.window.document.body.textContent).not.toContain('Tickets');
    expect(dom.window.document.body.textContent).toContain('Services');
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
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('service-a');
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).not.toContain('git@gitlab.com:company/service-a.git');
    expect(dom.window.document.getElementById('projectServicesList')).toBeNull();
    expect(dom.window.document.getElementById('projectStandaloneServicesSection')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2AgentsList')?.textContent).toContain('Architect');
    expect(dom.window.document.getElementById('agentsV2WorkflowsList')?.textContent).toContain('Full Testing');
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Check calculation');

    page.showProjectsIndex();
    expect(page.state.selectedProjectId).toBeNull();
    expect(dom.window.document.getElementById('agentsV2ProjectsView')?.classList.contains('hidden')).toBe(false);
  });

  it('clicking a repository card opens the repository workspace without an Open Service button', async () => {
    const linkedService = { ...service('service-1'), repositoryId: repository().id };
    const { dom, page } = await openedProject(api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository(undefined, project().id, 'some_bridge', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([linkedService]),
      getServiceRuntime: vi.fn().mockResolvedValue({ status: 'RUNNING', connection: 'LOCAL', provider: 'DOCKER', targetIdentity: 'api', uptime: 'PT1H', metadata: {} }),
      listServiceLogSources: vi.fn().mockResolvedValue([])
    }));

    const card = dom.window.document.querySelector<HTMLElement>('[data-repository-id="88888888-8888-4888-8888-888888888888"]')!;
    expect(card.textContent).toContain('some_bridge');
    expect(card.textContent).toContain('main · Clean');
    expect(card.textContent).toContain('RUNNING');
    expect(card.textContent).not.toContain('Open');
    card.click();
    await flushAsync();

    expect(dom.window.location.hash).toBe(`#/projects/${project().id}/repositories/88888888-8888-4888-8888-888888888888`);
    expect(dom.window.document.getElementById('projectLogsTitle')?.textContent).toBe('some_bridge');
    expect(page.state.view).toBe('repository');
    page.dispose();
  });

  it('repository with no linked Service shows Runtime not configured without creating a fake Service', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([]),
      createService: vi.fn()
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryRuntimeSummary')?.textContent).toBe('Runtime not configured');
    expect(dom.window.document.getElementById('repositoryRuntimeDetails')?.textContent).toContain('Runtime not configured');
    expect(fakeApi.createService).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('projectLogsSourcesSection')?.classList.contains('hidden')).toBe(true);
    page.dispose();
  });

  it('does not render Runtime not configured when Service listing fails on Project page', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockRejectedValue(new Error('Services unavailable.'))
    });
    const { dom, page } = await openedProject(fakeApi);

    const summary = dom.window.document.querySelector('[data-repository-runtime-status="repo-1"]')!;
    expect(summary.textContent).toBe('UNKNOWN');
    expect(summary.textContent).not.toBe('NOT CONFIGURED');
    page.dispose();
  });

  it('does not render Runtime not configured when Service listing fails in Repository workspace', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockRejectedValue(new Error('Services unavailable.'))
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryRuntimeSummary')?.textContent).toBe('Runtime status unknown');
    expect(dom.window.document.getElementById('repositoryRuntimeDetails')?.textContent).toContain('Runtime workloads failed to load');
    expect(dom.window.document.getElementById('repositoryRuntimeConfigure')?.hasAttribute('disabled')).toBe(true);
    expect(dom.window.document.getElementById('repositoryRuntimeSummary')?.textContent).not.toBe('Runtime not configured');
    page.dispose();
  });

  it('Configure Runtime creates a Service with the opened repositoryId', async () => {
    const createService = vi.fn().mockResolvedValue({ ...service('service-1'), repositoryId: 'repo-1' });
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([]),
      createService,
      listSshConnections: vi.fn().mockResolvedValue([])
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    expect(dom.window.document.getElementById('projectServiceRepositoryField')?.classList.contains('hidden')).toBe(true);
    (dom.window.document.getElementById('projectServiceName') as HTMLInputElement).value = 'api';
    (dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).value = 'api-container';
    await page.submitService(new dom.window.Event('submit'));

    expect(createService).toHaveBeenCalledWith(project().id, {
      name: 'api',
      repositoryId: 'repo-1',
      runtimeTarget: {
        connection: 'LOCAL',
        sshConnectionId: null,
        provider: 'DOCKER',
        container: 'api-container',
        unit: null
      }
    });
    page.dispose();
  });

  it('Docker candidates populate Configure Runtime as editable target options', async () => {
    const discoverRuntimeTargets = vi.fn().mockResolvedValue([
      { id: 'forge-agent', provider: 'DOCKER' },
      { id: 'forge-nexus', provider: 'DOCKER' }
    ]);
    const fakeApi = api({
      discoverRuntimeTargets,
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();

    const options = [...dom.window.document.querySelectorAll<HTMLOptionElement>('#projectServiceContainers option')]
      .map((option) => option.value);
    expect(discoverRuntimeTargets).toHaveBeenCalledWith(project().id, {
      connection: 'LOCAL',
      sshConnectionId: null,
      provider: 'DOCKER'
    });
    expect(options).toEqual(['forge-agent', 'forge-nexus']);
    expect((dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).readOnly).toBe(false);
    page.dispose();
  });

  it('systemd units populate Configure Runtime and discovery refreshes when Provider changes', async () => {
    const discoverRuntimeTargets = vi.fn((projectId: string, request: any) => Promise.resolve(
      request.provider === 'SYSTEMD'
        ? [{ id: 'forge-agent.service', provider: 'SYSTEMD' }]
        : [{ id: 'forge-agent', provider: 'DOCKER' }]
    ));
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets,
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    selectValue(dom, 'projectServiceProvider', 'SYSTEMD');
    await flushAsync();

    const units = [...dom.window.document.querySelectorAll<HTMLOptionElement>('#projectServiceUnits option')]
      .map((option) => option.value);
    expect(units).toEqual(['forge-agent.service']);
    expect(discoverRuntimeTargets).toHaveBeenLastCalledWith(project().id, {
      connection: 'LOCAL',
      sshConnectionId: null,
      provider: 'SYSTEMD'
    });
    page.dispose();
  });

  it('runtime discovery refreshes when LOCAL or SSH profile changes', async () => {
    const sshOne = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const sshTwo = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    const discoverRuntimeTargets = vi.fn().mockResolvedValue([]);
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets,
      listSshConnections: vi.fn().mockResolvedValue([
        { id: sshOne, name: 'sandbox', host: 'sandbox', port: 22, username: 'forge' },
        { id: sshTwo, name: 'prod', host: 'prod', port: 22, username: 'forge' }
      ]),
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    selectValue(dom, 'projectServiceConnection', 'SSH');
    await flushAsync();
    selectValue(dom, 'projectServiceSsh', sshOne);
    await flushAsync();
    selectValue(dom, 'projectServiceSsh', sshTwo);
    await flushAsync();

    expect(discoverRuntimeTargets).toHaveBeenCalledWith(project().id, {
      connection: 'LOCAL',
      sshConnectionId: null,
      provider: 'DOCKER'
    });
    expect(discoverRuntimeTargets).toHaveBeenCalledWith(project().id, {
      connection: 'SSH',
      sshConnectionId: sshOne,
      provider: 'DOCKER'
    });
    expect(discoverRuntimeTargets).toHaveBeenLastCalledWith(project().id, {
      connection: 'SSH',
      sshConnectionId: sshTwo,
      provider: 'DOCKER'
    });
    page.dispose();
  });

  it('stale runtime discovery cannot overwrite current provider candidates', async () => {
    const dockerDiscovery = deferred<any[]>();
    const discoverRuntimeTargets = vi.fn((_projectId: string, request: any) =>
      request.provider === 'DOCKER'
        ? dockerDiscovery.promise
        : Promise.resolve([{ id: 'forge-nexus.service', provider: 'SYSTEMD' }]));
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets,
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    selectValue(dom, 'projectServiceProvider', 'SYSTEMD');
    await flushAsync();
    dockerDiscovery.resolve([{ id: 'old-container', provider: 'DOCKER' }]);
    await flushAsync();

    const units = [...dom.window.document.querySelectorAll<HTMLOptionElement>('#projectServiceUnits option')]
      .map((option) => option.value);
    const containers = [...dom.window.document.querySelectorAll<HTMLOptionElement>('#projectServiceContainers option')]
      .map((option) => option.value);
    expect(units).toEqual(['forge-nexus.service']);
    expect(containers).toEqual([]);
    page.dispose();
  });

  it('modal close invalidates pending runtime discovery', async () => {
    const discovery = deferred<any[]>();
    const discoverRuntimeTargets = vi.fn().mockReturnValue(discovery.promise);
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets,
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    dom.window.document.getElementById('projectServiceCancel')?.click();
    discovery.resolve([{ id: 'old-container', provider: 'DOCKER' }]);
    await flushAsync();

    expect([...dom.window.document.querySelectorAll('#projectServiceContainers option')]).toHaveLength(0);
    expect(dom.window.document.getElementById('projectServiceDialog')?.hasAttribute('open')).toBe(false);
    page.dispose();
  });

  it('discovery failure keeps manual runtime target entry available', async () => {
    const discoverRuntimeTargets = vi.fn().mockRejectedValue(new Error('offline'));
    const linked = { ...service('service-1'), repositoryId: 'repo-1', runtimeTarget: {
      connection: 'LOCAL', sshConnectionId: null, provider: 'DOCKER', container: 'custom-runtime', unit: null
    } };
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets,
      listServices: vi.fn().mockResolvedValue([linked]),
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();

    expect((dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).value).toBe('custom-runtime');
    expect(dom.window.document.getElementById('projectServiceDiscoveryError')?.textContent).toContain('Target discovery failed');
    page.dispose();
  });

  it('saved target missing from discovery remains visible and editable', async () => {
    const linked = { ...service('service-1'), repositoryId: 'repo-1', runtimeTarget: {
      connection: 'LOCAL', sshConnectionId: null, provider: 'DOCKER', container: 'custom-runtime', unit: null
    } };
    const { dom, page } = await openedRepository(api({
      discoverRuntimeTargets: vi.fn().mockResolvedValue([{ id: 'forge-agent', provider: 'DOCKER' }]),
      listServices: vi.fn().mockResolvedValue([linked]),
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ])
    }), 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();

    const options = [...dom.window.document.querySelectorAll<HTMLOptionElement>('#projectServiceContainers option')]
      .map((option) => option.value);
    expect((dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).value).toBe('custom-runtime');
    expect(options).toEqual(['custom-runtime', 'forge-agent']);
    page.dispose();
  });

  it('rejects stale Configure Runtime modal context after navigating to another repository', async () => {
    const repoA = repository('repo-a', project().id, 'api', true, branchGitState('CLEAN', 'main'));
    const repoB = repository('repo-b', project().id, 'worker', true, branchGitState('CLEAN', 'main'));
    const sshLoad = deferred<any[]>();
    const listSshConnections = vi.fn().mockResolvedValue([]);
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([repoA, repoB]),
      listServices: vi.fn().mockResolvedValue([]),
      listSshConnections
    });
    const { dom, page } = await openedRepository(fakeApi, repoA.id);
    listSshConnections.mockReturnValueOnce(sshLoad.promise);

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    await page.openRepositoryWorkspace(project().id, repoB.id);
    sshLoad.resolve([]);
    await flushAsync();

    expect(dom.window.document.getElementById('projectServiceDialog')?.hasAttribute('open')).toBe(false);
    expect(page.state.configuringRepositoryId).not.toBe(repoA.id);
    expect(page.state.selectedRepositoryId).toBe(repoB.id);
    page.dispose();
  });

  it('editing repository runtime uses Service API and does not modify Repository', async () => {
    const linked = { ...service('service-1'), repositoryId: 'repo-1' };
    const updateService = vi.fn().mockResolvedValue(linked);
    const refreshProjectRepository = vi.fn().mockResolvedValue(repository('repo-1', project().id, 'api', true));
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([linked]),
      listServiceLogSources: vi.fn().mockResolvedValue([]),
      updateService,
      refreshProjectRepository
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeConfigure')?.click();
    await flushAsync();
    (dom.window.document.getElementById('projectServiceContainer') as HTMLInputElement).value = 'api-v2';
    await page.submitService(new dom.window.Event('submit'));

    expect(updateService).toHaveBeenCalledWith(
      project().id,
      linked.id,
      expect.objectContaining({
        repositoryId: 'repo-1',
        runtimeTarget: expect.objectContaining({ container: 'api-v2' })
      })
    );
    expect(refreshProjectRepository).not.toHaveBeenCalled();
    page.dispose();
  });

  it('one linked Service opens directly with service-scoped Logs', async () => {
    const linked = { ...service('service-1'), repositoryId: 'repo-1', name: 'api runtime' };
    const listServiceLogSources = vi.fn().mockResolvedValue([{
      id: 'source-1',
      name: 'API logs',
      enabled: true,
      serviceId: linked.id,
      connection: 'LOCAL',
      provider: 'DOCKER'
    }]);
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([linked]),
      listServiceLogSources
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryRuntimeServices')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('repositoryRuntimeSummary')?.textContent).toContain('api runtime');
    expect(dom.window.document.getElementById('projectLogsSources')?.textContent).toContain('API logs');
    expect(listServiceLogSources).toHaveBeenCalledWith(project().id, linked.id);
    page.dispose();
  });

  it('multiple linked Services are represented explicitly and selectable', async () => {
    const apiService = { ...service('service-api'), repositoryId: 'repo-1', name: 'api' };
    const workerService = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker' };
    const getServiceRuntime = vi.fn((_projectId: string, serviceId: string) => Promise.resolve({
      status: serviceId === workerService.id ? 'STOPPED' : 'RUNNING',
      connection: 'LOCAL',
      provider: 'DOCKER',
      targetIdentity: serviceId,
      metadata: {}
    }));
    const listServiceLogSources = vi.fn().mockResolvedValue([]);
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([workerService, apiService]),
      getServiceRuntime,
      listServiceLogSources
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    const selector = dom.window.document.getElementById('repositoryRuntimeServices')!;
    expect(selector.classList.contains('hidden')).toBe(false);
    expect([...selector.querySelectorAll('[data-runtime-service-id]')].map((element) => element.textContent?.trim()))
      .toEqual(['api', 'worker']);
    expect(page.state.selectedRuntimeServiceId).toBe(apiService.id);

    selector.querySelector<HTMLElement>('[data-runtime-service-id="service-worker"]')?.click();
    await flushAsync();

    expect(page.state.selectedRuntimeServiceId).toBe(workerService.id);
    expect(dom.window.document.getElementById('repositoryRuntimeSummary')?.textContent).toContain('worker');
    expect(listServiceLogSources).toHaveBeenLastCalledWith(project().id, workerService.id);
    page.dispose();
  });

  it('adds another Service with an independent provider and target from a repository that already has one linked Service', async () => {
    const existing = { ...service('service-api'), repositoryId: 'repo-1', name: 'api' };
    const added = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker', runtimeTarget: {
      connection: 'LOCAL', sshConnectionId: null, provider: 'SYSTEMD', container: null, unit: 'worker.service'
    } };
    const createService = vi.fn().mockResolvedValue(added);
    const listServices = vi.fn()
      .mockResolvedValueOnce([existing])
      .mockResolvedValueOnce([existing])
      .mockResolvedValueOnce([existing, added]);
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices,
      createService,
      listServiceLogSources: vi.fn().mockResolvedValue([]),
      discoverRuntimeTargets: vi.fn().mockResolvedValue([{ id: 'worker.service', provider: 'SYSTEMD' }]),
      listSshConnections: vi.fn().mockResolvedValue([])
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.getElementById('repositoryRuntimeAdd')?.click();
    await flushAsync();
    expect(dom.window.document.getElementById('projectServiceDialogTitle')?.textContent).toBe('Configure Runtime');
    expect(dom.window.document.getElementById('projectServiceRepositoryField')?.classList.contains('hidden')).toBe(true);
    selectValue(dom, 'projectServiceProvider', 'SYSTEMD');
    await flushAsync();
    (dom.window.document.getElementById('projectServiceName') as HTMLInputElement).value = 'worker';
    (dom.window.document.getElementById('projectServiceUnit') as HTMLInputElement).value = 'worker.service';
    await page.submitService(new dom.window.Event('submit'));
    await flushAsync();

    expect(createService).toHaveBeenCalledWith(project().id, expect.objectContaining({
      name: 'worker',
      repositoryId: 'repo-1',
      runtimeTarget: expect.objectContaining({ provider: 'SYSTEMD', unit: 'worker.service' })
    }));
    expect(page.state.selectedRuntimeServiceId).toBe(existing.id);
    expect(dom.window.document.getElementById('repositoryRuntimeServices')?.textContent).toContain('worker');
    page.dispose();
  });

  it('deletes the selected repository Service without deleting the repository', async () => {
    const apiService = { ...service('service-api'), repositoryId: 'repo-1', name: 'api' };
    const workerService = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker' };
    const deleteService = vi.fn().mockResolvedValue({});
    const listServices = vi.fn()
      .mockResolvedValueOnce([apiService, workerService])
      .mockResolvedValueOnce([apiService, workerService])
      .mockResolvedValueOnce([apiService]);
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices,
      deleteService,
      listServiceLogSources: vi.fn().mockResolvedValue([])
    });
    const { dom, page } = await openedRepository(fakeApi, 'repo-1');
    Object.defineProperty(dom.window, 'confirm', { value: vi.fn(() => true), configurable: true });

    dom.window.document.querySelector<HTMLElement>('[data-runtime-service-id="service-worker"]')?.click();
    await flushAsync();
    dom.window.document.getElementById('repositoryRuntimeDelete')?.click();
    await flushAsync();

    expect(deleteService).toHaveBeenCalledWith(project().id, workerService.id);
    expect(page.state.selectedRuntimeServiceId).toBe(apiService.id);
    expect(dom.window.document.getElementById('repositoryRuntimeServices')?.classList.contains('hidden')).toBe(true);
    page.dispose();
  });

  it('renders mixed multi-Service repository summary without presenting it as RUNNING', async () => {
    const apiService = { ...service('service-api'), repositoryId: 'repo-1', name: 'api' };
    const workerService = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker' };
    const sidecarService = { ...service('service-sidecar'), repositoryId: 'repo-1', name: 'sidecar' };
    const getServiceRuntime = vi.fn((_projectId: string, serviceId: string) => Promise.resolve({
      status: serviceId === apiService.id ? 'RUNNING' : serviceId === workerService.id ? 'STOPPED' : 'UNKNOWN',
      connection: 'LOCAL',
      provider: 'DOCKER',
      targetIdentity: serviceId,
      metadata: {}
    }));
    const { dom, page } = await openedProject(api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([apiService, workerService, sidecarService]),
      getServiceRuntime
    }));
    await flushAsync();

    const summary = dom.window.document.querySelector('[data-repository-runtime-status="repo-1"]')!;
    expect(summary.textContent).toBe('3 services · 1 running · 1 stopped · 1 unknown');
    expect(summary.textContent).not.toBe('RUNNING');
    expect(summary.closest('.repository-runtime-summary')?.classList.contains('repository-runtime-running')).toBe(false);
    page.dispose();
  });

  it('renders multi-Service repository summary with visible failure tone', async () => {
    const apiService = { ...service('service-api'), repositoryId: 'repo-1', name: 'api', runtimeStatus: 'RUNNING' };
    const workerService = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker', runtimeStatus: 'FAILED' };
    const { dom, page } = await openedProject(api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([apiService, workerService]),
      getServiceRuntime: vi.fn((_projectId: string, serviceId: string) => Promise.resolve({
        status: serviceId === workerService.id ? 'FAILED' : 'RUNNING',
        metadata: {}
      }))
    }));
    await flushAsync();

    const summary = dom.window.document.querySelector('[data-repository-runtime-status="repo-1"]')!;
    expect(summary.textContent).toBe('2 services · 1 running · 1 failed');
    expect(summary.closest('.repository-runtime-summary')?.classList.contains('repository-runtime-failed')).toBe(true);
    page.dispose();
  });

  it('standalone Service remains accessible from the Project page', async () => {
    const standalone = { ...service('standalone-1'), name: 'worker' };
    const fakeApi = api({
      listServices: vi.fn().mockResolvedValue([standalone]),
      getService: vi.fn().mockResolvedValue(standalone),
      listServiceLogSources: vi.fn().mockResolvedValue([])
    });
    const { dom, page } = await openedProject(fakeApi);

    const section = dom.window.document.getElementById('projectStandaloneServicesSection')!;
    expect(section.classList.contains('hidden')).toBe(false);
    expect(section.textContent).toContain('worker');
    section.querySelector<HTMLElement>('[data-standalone-service-id="standalone-1"]')?.click();
    await flushAsync();

    expect(dom.window.location.hash).toBe(`#/projects/${project().id}/services/standalone-1`);
    expect(dom.window.document.getElementById('projectLogsTitle')?.textContent).toBe('worker');
    page.dispose();
  });

  it('project-level Logs remain project-scoped after repository workspace changes', async () => {
    const listLogSources = vi.fn().mockResolvedValue([{
      id: 'project-source',
      name: 'Project logs',
      enabled: true,
      serviceId: null,
      connection: 'LOCAL',
      provider: 'DOCKER'
    }]);
    const listServiceLogSources = vi.fn().mockResolvedValue([]);
    const { dom, page } = await openedProject(api({ listLogSources, listServiceLogSources }));

    dom.window.document.getElementById('projectLogsOpen')?.click();
    await flushAsync();

    expect(listLogSources).toHaveBeenCalledWith(project().id);
    expect(listServiceLogSources).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('projectLogsSources')?.textContent).toContain('Project logs');
    page.dispose();
  });

  it('stale Repository workspace runtime responses cannot overwrite the selected repository', async () => {
    const repoOne = repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'));
    const repoTwo = repository('repo-2', project().id, 'worker', true, branchGitState('CLEAN', 'main'));
    const serviceOne = { ...service('service-1'), repositoryId: repoOne.id };
    const serviceTwo = { ...service('service-2'), repositoryId: repoTwo.id };
    let resolveOldRuntime: (value: any) => void = () => {};
    const oldRuntime = new Promise((resolve) => { resolveOldRuntime = resolve; });
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([repoOne, repoTwo]),
      listServices: vi.fn().mockResolvedValue([serviceOne, serviceTwo]),
      listServiceLogSources: vi.fn().mockResolvedValue([]),
      getServiceRuntime: vi.fn((_projectId: string, serviceId: string) =>
        serviceId === serviceOne.id
          ? oldRuntime
          : Promise.resolve({ status: 'STOPPED', connection: 'LOCAL', provider: 'DOCKER', targetIdentity: 'worker', metadata: {} }))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openRepositoryWorkspace(project().id, repoOne.id);
    await page.openRepositoryWorkspace(project().id, repoTwo.id);
    resolveOldRuntime({ status: 'RUNNING', connection: 'LOCAL', provider: 'DOCKER', targetIdentity: 'api', metadata: {} });
    await flushAsync();

    expect(dom.window.document.getElementById('projectLogsTitle')?.textContent).toBe('worker');
    expect(dom.window.document.getElementById('repositoryRuntimeStatus')?.textContent).toBe('STOPPED');
    page.dispose();
  });

  it('Repository workspace disposes its EventSource when leaving', async () => {
    const streams: Array<{ closed: boolean }> = [];
    class EventSourceFake {
      closed = false;
      constructor() {
        streams.push(this);
      }
      addEventListener() {}
      close() {
        this.closed = true;
      }
    }
    const linked = { ...service('service-1'), repositoryId: 'repo-1' };
    const dom = agentProjectsDom();
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([linked]),
      listServiceLogSources: vi.fn().mockResolvedValue([{
        id: 'source-1',
        name: 'API logs',
        enabled: true,
        serviceId: linked.id,
        connection: 'LOCAL',
        provider: 'DOCKER'
      }]),
      logStreamUrl: vi.fn().mockReturnValue('/stream')
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await page.openRepositoryWorkspace(project().id, 'repo-1');
    dom.window.document.getElementById('projectLogsLive')?.click();

    expect(streams).toHaveLength(1);
    dom.window.document.getElementById('projectLogsBack')?.click();
    await flushAsync();

    expect(streams[0]!.closed).toBe(true);
    expect(dom.window.document.getElementById('projectLogsWorkspace')?.classList.contains('hidden')).toBe(true);
    page.dispose();
  });

  it('Repository Refresh and Pull do not close or recreate active service Logs', async () => {
    const streams: Array<{ closed: boolean }> = [];
    class EventSourceFake {
      closed = false;
      constructor() {
        streams.push(this);
      }
      addEventListener() {}
      close() {
        this.closed = true;
      }
    }
    const linked = { ...service('service-1'), repositoryId: 'repo-1' };
    const refreshed = repository('repo-1', project().id, 'api', true, behindGitState());
    const pulled = repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'));
    const listServiceLogSources = vi.fn().mockResolvedValue([{
      id: 'source-1',
      name: 'API logs',
      enabled: true,
      serviceId: linked.id,
      connection: 'LOCAL',
      provider: 'DOCKER'
    }]);
    const getServiceRuntime = vi.fn().mockResolvedValue({ status: 'RUNNING', connection: 'LOCAL', provider: 'DOCKER', targetIdentity: 'api', metadata: {} });
    const dom = agentProjectsDom();
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([linked]),
      getServiceRuntime,
      listServiceLogSources,
      refreshProjectRepository: vi.fn().mockResolvedValue(refreshed),
      pullProjectRepository: vi.fn().mockResolvedValue(pulled),
      logStreamUrl: vi.fn().mockReturnValue('/stream')
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    await page.openRepositoryWorkspace(project().id, 'repo-1');
    dom.window.document.getElementById('projectLogsLive')?.click();
    expect(streams).toHaveLength(1);
    const logSourceLoads = listServiceLogSources.mock.calls.length;
    const runtimeLoads = getServiceRuntime.mock.calls.length;

    dom.window.document.querySelector<HTMLButtonElement>('[data-refresh-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(streams).toHaveLength(1);
    expect(streams[0]!.closed).toBe(false);
    expect(listServiceLogSources).toHaveBeenCalledTimes(logSourceLoads);
    expect(getServiceRuntime).toHaveBeenCalledTimes(runtimeLoads);

    dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(streams).toHaveLength(1);
    expect(streams[0]!.closed).toBe(false);
    expect(listServiceLogSources).toHaveBeenCalledTimes(logSourceLoads);
    expect(getServiceRuntime).toHaveBeenCalledTimes(runtimeLoads);
    page.dispose();
  });

  it('stale Service A Logs load cannot dispose current Service B Logs', async () => {
    const streams: Array<{ closed: boolean }> = [];
    class EventSourceFake {
      closed = false;
      constructor() {
        streams.push(this);
      }
      addEventListener() {}
      close() {
        this.closed = true;
      }
    }
    const apiService = { ...service('service-api'), repositoryId: 'repo-1', name: 'api' };
    const workerService = { ...service('service-worker'), repositoryId: 'repo-1', name: 'worker' };
    const apiLogs = deferred<any[]>();
    const listServiceLogSources = vi.fn((_projectId: string, serviceId: string) =>
      serviceId === apiService.id
        ? apiLogs.promise
        : Promise.resolve([{
          id: 'worker-source',
          name: 'Worker logs',
          enabled: true,
          serviceId: workerService.id,
          connection: 'LOCAL',
          provider: 'DOCKER'
        }]));
    const dom = agentProjectsDom();
    Object.defineProperty(dom.window, 'EventSource', { value: EventSourceFake, configurable: true });
    const fakeApi = api({
      listProjectRepositories: vi.fn().mockResolvedValue([
        repository('repo-1', project().id, 'api', true, branchGitState('CLEAN', 'main'))
      ]),
      listServices: vi.fn().mockResolvedValue([apiService, workerService]),
      listServiceLogSources,
      logStreamUrl: vi.fn().mockReturnValue('/stream')
    });
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(project().id);
    const opening = page.openRepositoryWorkspace(project().id, 'repo-1');
    await flushAsync();

    dom.window.document.querySelector<HTMLElement>('[data-runtime-service-id="service-worker"]')?.click();
    await flushAsync();
    dom.window.document.getElementById('projectLogsLive')?.click();
    expect(streams).toHaveLength(1);

    apiLogs.resolve([{
      id: 'api-source',
      name: 'API logs',
      enabled: true,
      serviceId: apiService.id,
      connection: 'LOCAL',
      provider: 'DOCKER'
    }]);
    await opening;
    await flushAsync();

    expect(page.state.selectedRuntimeServiceId).toBe(workerService.id);
    expect(dom.window.document.getElementById('projectLogsSources')?.textContent).toContain('Worker logs');
    expect(streams).toHaveLength(1);
    expect(streams[0]!.closed).toBe(false);
    page.dispose();
  });

  it('imports repository into Project and refreshes repository list', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a'),
          repository('repo-2', project().id, 'service-b')
        ]),
      importProjectRepository: vi.fn(() => Promise.resolve(repository('repo-1')))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('No repositories yet.');

    dom.window.document.getElementById('agentsV2ImportRepository')?.click();
    const input = dom.window.document.getElementById('agentsV2RepositoryUrl') as HTMLInputElement;
    input.value = ' git@gitlab.com:company/service-a.git ';
    dom.window.document.getElementById('agentsV2RepositoryForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.importProjectRepository).toHaveBeenCalledWith(project().id, {
      remoteUrl: 'git@gitlab.com:company/service-a.git'
    });
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('service-a');
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('service-b');
  });

  it('shows Clone in the repository workspace for an uncloned repository and refreshes after clone', async () => {
    const cloneRequest = deferred<any>();
    const before = [repository('repo-1', project().id, 'service-a', false)];
    const after = [repository('repo-1', project().id, 'service-a', true)];
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(after),
      cloneProjectRepository: vi.fn(() => cloneRequest.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    const repoOneClone = dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]');
    expect(dom.window.document.getElementById('repositorySourceDetails')?.textContent).toContain('service-a');
    expect(repoOneClone).not.toBeNull();
    expect(repoOneClone?.disabled).toBe(false);

    repoOneClone?.click();
    await flushAsync();
    const repoOneCloneInFlight = dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]');
    expect(repoOneCloneInFlight?.disabled).toBe(true);
    repoOneCloneInFlight?.click();
    expect(fakeApi.cloneProjectRepository).toHaveBeenCalledTimes(1);

    cloneRequest.resolve(repository('repo-1', project().id, 'service-a', true));
    await flushAsync();

    expect(fakeApi.cloneProjectRepository).toHaveBeenCalledWith(project().id, 'repo-1');
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(2);
    expect(dom.window.document.querySelector('[data-clone-repository-id="repo-1"]')).toBeNull();
  });

  it('renders branch and Clean state for cloned repositories', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, branchGitState('CLEAN', 'main'))
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    const text = dom.window.document.getElementById('repositorySourceDetails')?.textContent || '';
    expect(text).toContain('service-a');
    expect(text).toContain('git@gitlab.com:company/service-a.git');
    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('main · Clean');
    expect(dom.window.document.querySelector('[data-clone-repository-id="repo-1"]')).toBeNull();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
    expect(dom.window.document.querySelector('[data-refresh-repository-id="repo-1"]')?.closest('.repository-actions')).not.toBeNull();
    const source = consoleSourceText();
    expect(source).toMatch(/\.repository-list\s*\{[^}]*grid-template-columns: repeat\(2, minmax\(0, 1fr\)\);/);
    expect(source).toMatch(/@media \(max-width: 1000px\)[\s\S]*\.repository-list,[\s\S]*\.repository-detail-grid\s*\{[^}]*grid-template-columns: 1fr;/);
  });

  it('refreshes remote state only after an explicit repository action', async () => {
    const refreshRequest = deferred<any>();
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, branchGitState('CLEAN', 'main'))
        ])
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, branchGitState('CLEAN', 'main'))
        ])
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, behindGitState())
        ]),
      refreshProjectRepository: vi.fn(() => refreshRequest.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(fakeApi.refreshProjectRepository).not.toHaveBeenCalled();
    const refresh = dom.window.document.querySelector<HTMLButtonElement>('[data-refresh-repository-id="repo-1"]');
    expect(refresh?.disabled).toBe(false);
    const beforeHash = dom.window.location.hash;
    refresh?.click();
    await flushAsync();
    expect(dom.window.location.hash).toBe(beforeHash);
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-refresh-repository-id="repo-1"]')?.disabled).toBe(true);

    refreshRequest.resolve(repository('repo-1', project().id, 'service-a', true, behindGitState()));
    await flushAsync();

    expect(fakeApi.refreshProjectRepository).toHaveBeenCalledWith(project().id, 'repo-1');
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(2);
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(false);
  });

  it('renders enabled Pull action when backend reports pullAvailable', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, behindGitState())
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('main · Clean');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(false);
  });

  it('renders Dirty state with disabled Pull', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, branchGitState('DIRTY', 'main'))
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('main · Dirty');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('renders unsafe dirty repository with disabled Pull', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, conflictedGitState())
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('main · Dirty');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('renders detached HEAD state with disabled Pull', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, detachedGitState())
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('detached · Clean');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('disables Pull when cloned repository has no upstream', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, noUpstreamGitState())
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.getElementById('repositoryOverviewSummary')?.textContent).toContain('main · Clean');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('disables Pull when cloned repository is diverged', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, divergedGitState())
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('renders invalid local checkout for cloned invalid repositories', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', true, null)
      ]))
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    const text = dom.window.document.getElementById('repositoryOverviewSummary')?.textContent || '';
    expect(text).toContain('Invalid Git checkout');
    expect(dom.window.document.querySelector('[data-clone-repository-id="repo-1"]')).toBeNull();
    expect(dom.window.document.querySelector('[data-pull-repository-id="repo-1"]')).toBeNull();
  });

  it('does not render any Check action for repositories', async () => {
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', false),
        repository('repo-2', project().id, 'service-b', true, branchGitState('CLEAN', 'main')),
        repository('repo-3', project().id, 'service-c', true, behindGitState())
      ]))
    });
    const { dom } = await openedProject(fakeApi);

    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).not.toContain('Check');
  });

  it('pulls a safe repository from the repository workspace and refreshes state', async () => {
    const pullRequest = deferred<any>();
    const before = [repository('repo-1', project().id, 'service-a', true, behindGitState())];
    const after = [repository('repo-1', project().id, 'service-a', true, branchGitState('CLEAN', 'main', false))];
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(after),
      pullProjectRepository: vi.fn(() => pullRequest.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    const repoOnePull = dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]');
    expect(repoOnePull?.disabled).toBe(false);

    repoOnePull?.click();
    await flushAsync();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);

    dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(fakeApi.pullProjectRepository).toHaveBeenCalledTimes(1);

    pullRequest.resolve(repository('repo-1', project().id, 'service-a', true));
    await flushAsync();

    expect(fakeApi.pullProjectRepository).toHaveBeenCalledWith(project().id, 'repo-1');
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(2);
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('stale Project A Git action cannot mutate Project B repositories or errors', async () => {
    const projectA = project();
    const projectB = project('22222222-2222-4222-8222-222222222222', 'Other');
    const repoA = repository('repo-a', projectA.id, 'service-a', true, behindGitState());
    const repoB = repository('repo-b', projectB.id, 'service-b', true, branchGitState('CLEAN', 'main'));
    const pull = deferred<any>();
    const fakeApi = api({
      listProjects: vi.fn().mockResolvedValue([projectA, projectB]),
      listProjectRepositories: vi.fn((projectId: string) => Promise.resolve(projectId === projectA.id ? [repoA] : [repoB])),
      pullProjectRepository: vi.fn().mockReturnValue(pull.promise),
      listServices: vi.fn().mockResolvedValue([])
    });
    const dom = agentProjectsDom();
    const page = new AgentProjectsPage({ document: dom.window.document, window: dom.window, api: fakeApi });
    page.mount();
    await flushAsync();
    await page.openProject(projectA.id);

    dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-a"]')?.click();
    await flushAsync();
    await page.openProject(projectB.id);
    pull.resolve({ ...repoA, git: branchGitState('CLEAN', 'main'), projectId: projectA.id });
    await flushAsync();

    expect(page.state.selectedProjectId).toBe(projectB.id);
    expect(page.state.repositories).toEqual([repoB]);
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).toContain('service-b');
    expect(dom.window.document.getElementById('agentsV2RepositoriesList')?.textContent).not.toContain('service-a');
    expect(dom.window.document.getElementById('agentsV2RepositoriesError')?.textContent).toBe('');
    page.dispose();
  });

  it('pull failure refreshes repositories and restores backend-derived disabled state', async () => {
    const pullRequest = deferred<any>();
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, behindGitState())
        ])
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, behindGitState())
        ])
        .mockResolvedValueOnce([
          repository('repo-1', project().id, 'service-a', true, divergedGitState())
        ]),
      pullProjectRepository: vi.fn(() => pullRequest.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);

    pullRequest.reject(new Error('Pull blocked.'));
    await flushAsync();

    expect(fakeApi.pullProjectRepository).toHaveBeenCalledTimes(1);
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(3);
    expect(dom.window.document.getElementById('agentsV2RepositoriesError')?.textContent).toContain('Pull blocked.');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-pull-repository-id="repo-1"]')?.disabled).toBe(true);
  });

  it('re-enables repository Clone button after clone failure', async () => {
    const cloneRequest = deferred<any>();
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([
        repository('repo-1', project().id, 'service-a', false),
        repository('repo-2', project().id, 'service-b', false)
      ])),
      cloneProjectRepository: vi.fn(() => cloneRequest.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.disabled).toBe(true);

    cloneRequest.reject(new Error('Clone failed.'));
    await flushAsync();

    expect(fakeApi.cloneProjectRepository).toHaveBeenCalledTimes(1);
    expect(dom.window.document.getElementById('agentsV2RepositoriesError')?.textContent).toContain('Clone failed.');
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.disabled).toBe(false);
  });

  it('does not submit duplicate Clone actions from the repository workspace', async () => {
    const repoOneClone = deferred<any>();
    const before = [repository('repo-1', project().id, 'service-a', false)];
    const after = [repository('repo-1', project().id, 'service-a', true)];
    const fakeApi = api({
      listProjectRepositories: vi.fn()
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(before)
        .mockResolvedValueOnce(after),
      cloneProjectRepository: vi.fn(() => repoOneClone.promise)
    });
    const { dom } = await openedRepository(fakeApi, 'repo-1');

    dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.disabled).toBe(true);
    dom.window.document.querySelector<HTMLButtonElement>('[data-clone-repository-id="repo-1"]')?.click();
    await flushAsync();
    expect(fakeApi.cloneProjectRepository).toHaveBeenCalledTimes(1);

    repoOneClone.resolve(repository('repo-1', project().id, 'service-a', true));
    await flushAsync();

    expect(fakeApi.cloneProjectRepository).toHaveBeenCalledWith(project().id, 'repo-1');
    expect(dom.window.document.querySelector('[data-clone-repository-id="repo-1"]')).toBeNull();
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(2);
  });

  it('rejects blank repository URL locally', async () => {
    const { dom, fakeApi } = await openedProject();

    dom.window.document.getElementById('agentsV2ImportRepository')?.click();
    (dom.window.document.getElementById('agentsV2RepositoryUrl') as HTMLInputElement).value = '   ';
    dom.window.document.getElementById('agentsV2RepositoryForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.importProjectRepository).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2RepositoryModalError')?.textContent).toContain('Repository URL is required.');
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
    (dom.window.document.querySelector('#agentsV2TaskRepositories input') as HTMLInputElement).checked = true;
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
    const summary = dom.window.document.getElementById('agentsV2TaskExecutionSummary');
    const summaryToggle = dom.window.document.getElementById('agentsV2TaskExecutionSummaryToggle');
    expect(summary?.classList.contains('hidden')).toBe(true);
    expect(summaryToggle?.getAttribute('aria-expanded')).toBe('false');

    summaryToggle?.click();

    expect(summary?.classList.contains('hidden')).toBe(false);
    expect(summaryToggle?.getAttribute('aria-expanded')).toBe('true');

    await vi.advanceTimersByTimeAsync(4999);
    await flushAsync();
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(1);

    dom.window.document.getElementById('agentsV2TaskExecutionBack')?.click();
    await flushAsync();

    expect(dom.window.document.getElementById('agentsV2TaskExecution')?.classList.contains('hidden')).toBe(true);
    expect(dom.window.document.getElementById('agentsV2Workspace')?.classList.contains('hidden')).toBe(false);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
  });

  it('Task execution Result section waits while active and pretty-prints successful result', async () => {
    const fakeApi = api({
      getProjectTask: vi.fn()
        .mockResolvedValueOnce(taskDetail('task-1', [taskRun('run-active', 'RUNNING', '2026-08-13T10:00:00Z')]))
        .mockResolvedValueOnce({
          ...taskDetail('task-1', [taskRun('run-success', 'SUCCEEDED', '2026-08-13T10:01:00Z')]),
          result: { answer: 'done' }
        }),
      getWorkflowRun: vi.fn()
        .mockResolvedValueOnce(workflowRunDetail('run-active', 'RUNNING', [
          nodeRun('node-a', 'Analyzer', 'RUNNING', [], 30, 40)
        ], 'Active Flow'))
        .mockResolvedValueOnce({
          ...workflowRunDetail('run-success', 'SUCCEEDED', [
            nodeRun('node-b', 'Writer', 'SUCCEEDED', [], 30, 40, { answer: 'done' })
          ], 'Done Flow'),
          result: { answer: 'done' },
          resultSourceNodeRunId: 'node-b'
        })
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent)
      .toContain('Result not available yet.');

    await page.openTaskExecution('task-1');
    await flushAsync();

    const summary = dom.window.document.getElementById('agentsV2TaskExecutionSummary')!;
    expect(summary.textContent).toContain('Result');
    expect(summary.querySelector('.task-result-section pre')?.textContent).toContain('"answer": "done"');
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
    expect(details.querySelector('.node-run-output pre')?.textContent).toContain('"count": 8');
    expect(details.querySelector<HTMLDetailsElement>('.node-run-prompt-details')?.open).toBe(false);

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
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('Reviewer');
    expect(dom.window.document.getElementById('agentsV2TaskExecutionSummary')?.textContent).toContain('ASSERTION_FAILED');
  });

  it('modern execution board renders all runtimeGraph nodes before they execute', async () => {
    const graph = runtimeGraph([
      { id: 'a', agentName: 'A', position: { x: 20, y: 30 } },
      { id: 'b', agentName: 'B', position: { x: 260, y: 30 } },
      { id: 'c', agentName: 'C', position: { x: 500, y: 30 } }
    ], [
      portConnection('a-b', 'a-output', 'b-input'),
      portConnection('b-c', 'b-output', 'c-input')
    ]);
    const run = workflowRunDetail('run-new', 'RUNNING', [
      modernNodeRun('a-1', 'a', 'RUNNING', '2026-08-13T10:00:00Z')
    ], 'Modern Board', graph);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    expect(fakeApi.getWorkflow).not.toHaveBeenCalled();
    expect(dom.window.document.querySelectorAll('[data-execution-source-node-id]')).toHaveLength(3);
    expect(dom.window.document.querySelector('[data-execution-source-node-id="a"]')?.textContent).toContain('#1 RUNNING');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="b"]')?.textContent).toContain('0 runs');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="c"]')?.textContent).toContain('0 runs');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="b"]')?.textContent?.match(/0 runs/g)).toHaveLength(1);
    expect(dom.window.document.querySelector('[data-runtime-connection-id="a-b"]')).not.toBeNull();
    expect(dom.window.document.querySelector('[data-runtime-connection-id="b-c"]')).not.toBeNull();

    const details = dom.window.document.getElementById('agentsV2NodeRunDetails')!;
    expect(details.textContent).toContain('Select a node');
    const unexecuted = dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="b"]')!;
    unexecuted.click();
    expect(dom.window.document.querySelector('[data-execution-source-node-id="b"]')?.classList.contains('selected')).toBe(true);
    expect(details.textContent).toContain('B');
    expect(details.textContent).toContain('Not executed yet');
  });

  it('card selection follows the latest invocation while marker selection stays pinned', async () => {
    const graph = runtimeGraph([{ id: 'reviewer', agentName: 'Reviewer' }]);
    const first = modernNodeRun('reviewer-1', 'reviewer', 'RUNNING', '2026-08-13T10:00:00Z');
    const second = modernNodeRun('reviewer-2', 'reviewer', 'SUCCEEDED', '2026-08-13T10:01:00Z');
    const third = modernNodeRun('reviewer-3', 'reviewer', 'FAILED', '2026-08-13T10:02:00Z');
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [first], 'Selection Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="reviewer"]')!.click();
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-1');

    page.taskExecutionView.applyWorkflowRun(workflowRunDetail('run-new', 'RUNNING', [first, second], 'Selection Board', graph));
    page.taskExecutionView.render();
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-2');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')?.classList.contains('selected')).toBe(true);
    expect(dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')?.value).toBe('reviewer-2');
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('SUCCEEDED');

    const invocationSelect = dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')!;
    invocationSelect.value = 'reviewer-1';
    invocationSelect.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
    page.taskExecutionView.applyWorkflowRun(workflowRunDetail('run-new', 'RUNNING', [first, second, third], 'Selection Board', graph));
    page.taskExecutionView.render();
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-1');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')?.classList.contains('selected')).toBe(true);
    expect(dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')?.value).toBe('reviewer-1');
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('RUNNING');

    page.taskExecutionView.applyWorkflowRun(workflowRunDetail('run-new', 'RUNNING', [second, third], 'Selection Board', graph));
    page.taskExecutionView.render();
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-3');
    expect(dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')?.value).toBe('reviewer-3');
  });

  it('an unexecuted card follows its first invocation when it appears', async () => {
    const graph = runtimeGraph([{ id: 'reviewer', agentName: 'Reviewer' }]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Selection Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="reviewer"]')!.click();
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('Not executed yet');

    const first = modernNodeRun('reviewer-1', 'reviewer', 'RUNNING', '2026-08-13T10:00:00Z');
    page.taskExecutionView.applyWorkflowRun(workflowRunDetail('run-new', 'RUNNING', [first], 'Selection Board', graph));
    page.taskExecutionView.render();
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-1');
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('RUNNING');
  });

  it('FOLLOW_LATEST remains isolated to the selected repository', async () => {
    const repoA = repository('repo-a', project().id, 'repo-A');
    const repoB = repository('repo-b', project().id, 'repo-B');
    const graph = runtimeGraph([{ id: 'reviewer', agentName: 'Reviewer', scopeMode: 'PER_SCOPE' }]);
    const runFor = (id: string, repositoryId: string, createdAt: string) => ({
      ...modernNodeRun(id, 'reviewer', 'SUCCEEDED', createdAt),
      repositoryId
    });
    const a1 = runFor('a-1', repoA.id, '2026-08-13T10:00:00Z');
    const b1 = runFor('b-1', repoB.id, '2026-08-13T10:00:00Z');
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([repoA, repoB])),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve({
        ...workflowRunDetail('run-new', 'RUNNING', [a1, b1], 'Scoped Selection', graph),
        repositoryIds: [repoA.id, repoB.id]
      }))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    dom.window.document.querySelector<HTMLElement>(`[data-execution-repository-id="${repoA.id}"]`)!.click();
    const b2 = runFor('b-2', repoB.id, '2026-08-13T10:01:00Z');
    page.taskExecutionView.applyWorkflowRun({
      ...workflowRunDetail('run-new', 'RUNNING', [a1, b1, b2], 'Scoped Selection', graph),
      repositoryIds: [repoA.id, repoB.id]
    });
    page.taskExecutionView.render();

    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('a-1');
    expect(dom.window.document.querySelector(`[data-execution-repository-id="${repoA.id}"]`)?.classList.contains('selected')).toBe(true);
  });

  it('modern execution board renders canonical task boundaries as read-only topology', async () => {
    const graph = runtimeGraph([
      { id: 'root', agentName: 'Scoped Root', scopeMode: 'PER_SCOPE', inputs: [{ id: 'canonical-start', name: 'CHANGED_SKILL_WITH_A_VERY_LONG_NAME', order: 0 }] },
      { id: 'finish', agentName: 'Finish', outputs: [{ id: 'canonical-result', name: 'IMPLEMENTATION_COMPLETE_WITH_A_VERY_LONG_NAME', order: 0 }] }
    ], [portConnection('root-finish', 'root-output', 'finish-input')], 'canonical-start', 'canonical-result');
    const repoA = repository('repo-a', project().id, 'repo-A');
    const repoB = repository('repo-b', project().id, 'repo-B');
    const run = {
      ...workflowRunDetail('run-new', 'RUNNING', [], 'Boundary Board', graph),
      repositoryIds: [repoA.id, repoB.id]
    };
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([repoA, repoB])),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    expect(dom.window.document.querySelector('[data-execution-task-boundary="INPUT"]')?.textContent).toContain('TASK INPUT');
    expect(dom.window.document.querySelector('[data-execution-task-boundary="OUTPUT"]')?.textContent).toContain('TASK OUTPUT');
    expect(dom.window.document.querySelectorAll('[data-runtime-connection-id^="task-input:"]')).toHaveLength(2);
    expect(dom.window.document.querySelectorAll('[data-runtime-connection-id^="task-output:"]')).toHaveLength(1);
    expect(dom.window.document.querySelectorAll('[data-execution-run-chip-id]')).toHaveLength(0);
    expect(dom.window.document.querySelector('[data-runtime-port-id="canonical-start"]')?.getAttribute('title')).toBe('CHANGED_SKILL_WITH_A_VERY_LONG_NAME');
    expect(dom.window.document.querySelector('[data-runtime-port-id="canonical-result"]')?.getAttribute('title')).toBe('IMPLEMENTATION_COMPLETE_WITH_A_VERY_LONG_NAME');
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('Select a node');
  });

  it('isolates Runner pointer behavior and keeps long port labels bounded', () => {
    const styles = readFileSync(join(process.cwd(), 'src', 'operator', 'operator-ui.css'), 'utf8');

    expect(styles).toMatch(/\.execution-node\s*\{[^}]*pointer-events:\s*auto;/s);
    expect(styles).toMatch(/\.execution-board-node\s*\{[^}]*width:\s*288px;/s);
    expect(styles).toMatch(/\.execution-board-port-row span\s*\{[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/s);
    expect(styles).toMatch(/\.execution-board-port-row-input \.execution-port-anchor\s*\{[^}]*left:\s*0;/s);
    expect(styles).toMatch(/\.execution-board-port-row-output \.execution-port-anchor\s*\{[^}]*right:\s*0;/s);
    expect(styles).toMatch(/\.execution-board-card-grid\s*\{[^}]*grid-template-columns:\s*82px minmax\(0, 1fr\) 82px;/s);
    expect(styles).toMatch(/\.execution-board-port-column\s*\{[^}]*width:\s*82px;[^}]*overflow:\s*hidden;/s);
  });

  it('gives Task Execution the same bounded viewport-fill contract as Workflow Builder', () => {
    const styles = readFileSync(join(process.cwd(), 'src', 'operator', 'operator-ui.css'), 'utf8');

    expect(styles).toMatch(/\.task-execution-view\s*\{[^}]*grid-template-rows:\s*auto auto minmax\(0, 1fr\);[^}]*height:\s*calc\(100dvh - 80px\);[^}]*min-height:\s*0;/s);
    expect(styles).toMatch(/\.task-execution-layout\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s);
    expect(styles).toMatch(/\.task-execution-content\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s);
    expect(styles).toMatch(/\.execution-canvas\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;/s);
    expect(styles).toMatch(/\.execution-history\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;/s);
    expect(styles).toMatch(/\.node-run-details-panel\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*overflow:\s*auto;/s);
    expect(styles).not.toMatch(/\.task-execution-layout\s*\{[^}]*min-height:\s*580px;/s);
    expect(styles).not.toMatch(/\.execution-canvas\s*\{[^}]*min-height:\s*520px;/s);
    expect(styles).toMatch(/\.agents-v2-builder\s*\{[^}]*height:\s*calc\(100dvh - 80px\);[^}]*min-height:\s*0;/s);
  });

  it('keeps graph cards constant-size as invocation history grows and selects history in Node Details', async () => {
    const graph = runtimeGraph([{ id: 'reviewer', agentName: 'Reviewer' }]);
    const runs = Array.from({ length: 300 }, (_, index) => modernNodeRun(
      `reviewer-${index + 1}`,
      'reviewer',
      'SUCCEEDED',
      `2026-08-13T10:${String(Math.floor(index / 60)).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}Z`
    ));
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Scaling Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    for (const count of [0, 1, 10, 300]) {
      page.taskExecutionView.applyWorkflowRun(workflowRunDetail('run-new', 'RUNNING', runs.slice(0, count), 'Scaling Board', graph));
      page.taskExecutionView.render();
      const card = dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="reviewer"]')!;
      expect(card.querySelector('.execution-board-card-grid')?.children).toHaveLength(3);
      expect(card.querySelectorAll('button, [data-execution-run-chip-id]')).toHaveLength(0);
      expect(card.textContent).toContain(`${count} ${count === 1 ? 'run' : 'runs'}`);
    }

    dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="reviewer"]')!.click();
    const selector = dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')!;
    expect(selector.options).toHaveLength(300);
    expect(selector.value).toBe('reviewer-300');
    selector.value = 'reviewer-1';
    selector.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
    expect(page.taskExecutionView.state.selectedNodeRunId).toBe('reviewer-1');
  });

  it('measures short and long port anchors on the physical card boundaries', async () => {
    const graph = runtimeGraph([
      {
        id: 'short',
        agentName: 'Short',
        inputs: [{ id: 'short-input', name: 'IN', order: 0 }],
        outputs: [{ id: 'short-output', name: 'OUT', order: 0 }]
      },
      {
        id: 'long',
        agentName: 'Long',
        inputs: [{ id: 'long-input', name: 'IMPLEMENTATION_TASK_WITH_LONG_SUFFIX', order: 0 }],
        outputs: [{ id: 'long-output', name: 'IMPLEMENTATION_COMPLETE_WITH_LONG_SUFFIX', order: 0 }]
      }
    ]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Port Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    stubRect(dom.window.document.getElementById('agentsV2ExecutionCanvas'), 0, 0, 1000, 600);
    const projection = page.taskExecutionView.modernProjection();
    const shortNode = projection.nodeByUnit.get('short::__global__');
    const longNode = projection.nodeByUnit.get('long::__global__');
    stubRect(dom.window.document.querySelector('[data-execution-source-node-id="short"]'), 100, 60, MODERN_EXECUTION_CARD_WIDTH, 118);
    stubRect(dom.window.document.querySelector('[data-execution-source-node-id="long"]'), 520, 60, MODERN_EXECUTION_CARD_WIDTH, 118);
    stubAnchor(dom, 'short-input', 100, 100);
    stubAnchor(dom, 'short-output', 100 + MODERN_EXECUTION_CARD_WIDTH, 100);
    stubAnchor(dom, 'long-input', 520, 100);
    stubAnchor(dom, 'long-output', 520 + MODERN_EXECUTION_CARD_WIDTH, 100);

    expect(page.taskExecutionView.modernPortPoint(projection.portById.get('short-input'), shortNode, projection).x).toBe(100);
    expect(page.taskExecutionView.modernPortPoint(projection.portById.get('short-output'), shortNode, projection).x).toBe(388);
    expect(page.taskExecutionView.modernPortPoint(projection.portById.get('long-input'), longNode, projection).x).toBe(520);
    expect(page.taskExecutionView.modernPortPoint(projection.portById.get('long-output'), longNode, projection).x).toBe(808);
    expect(dom.window.document.querySelector('[data-runtime-port-id="long-input"]')?.getAttribute('title')).toBe('IMPLEMENTATION_TASK_WITH_LONG_SUFFIX');
  });

  it('modern execution board splits scoped histories and statuses by repository', async () => {
    const repoA = repository('repo-a', project().id, 'backend-service');
    const repoB = repository('repo-b', project().id, 'frontend-service');
    const graph = runtimeGraph([
      { id: 'analyzer', agentName: 'Analyzer', scopeMode: 'GLOBAL', position: { x: 20, y: 30 } },
      { id: 'implementer', agentName: 'Implementer', scopeMode: 'PER_SCOPE', position: { x: 300, y: 30 } }
    ], [portConnection('analyzer-implementer', 'analyzer-output', 'implementer-input')]);
    const run = {
      ...workflowRunDetail('run-new', 'FAILED', [
        ...['a-1', 'a-2', 'a-3'].map((id, index) => ({
          ...modernNodeRun(id, 'implementer', 'SUCCEEDED', `2026-08-13T10:0${index}:00Z`),
          repositoryId: repoA.id,
          executionFrameId: `frame-${index}`
        })),
        ...['b-1', 'b-2'].map((id, index) => ({
          ...modernNodeRun(id, 'implementer', index ? 'FAILED' : 'SUCCEEDED', `2026-08-13T10:1${index}:00Z`),
          repositoryId: repoB.id,
          executionFrameId: `frame-${index}`
        }))
      ], 'Scoped Board', graph),
      repositoryIds: [repoA.id, repoB.id]
    };
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([repoA, repoB])),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'FAILED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const scopedCards = [...dom.window.document.querySelectorAll<HTMLElement>('[data-execution-source-node-id="implementer"]')];
    expect(scopedCards).toHaveLength(2);
    expect(scopedCards.map((card) => card.dataset.executionRepositoryId)).toEqual([repoA.id, repoB.id]);
    expect(scopedCards[0]!.textContent).toContain('backend-service');
    expect(scopedCards[0]!.querySelectorAll('button, [data-execution-run-chip-id]')).toHaveLength(0);
    expect(scopedCards[0]!.classList.contains('execution-node-has-failed')).toBe(false);
    expect(scopedCards[1]!.textContent).toContain('frontend-service');
    expect(scopedCards[1]!.querySelectorAll('button, [data-execution-run-chip-id]')).toHaveLength(0);
    expect(scopedCards[1]!.classList.contains('execution-node-has-failed')).toBe(true);
    expect(dom.window.document.querySelector('[data-execution-source-node-id="analyzer"] .execution-board-repository')).toBeNull();

    scopedCards[0]!.click();
    expect(dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')?.value).toBe('a-3');
    scopedCards[1]!.click();
    expect(dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')?.value).toBe('b-2');
  });

  it('modern execution board projects not-yet-run scoped units without fake markers', async () => {
    const repoA = repository('repo-a', project().id, 'repo-A');
    const repoB = repository('repo-b', project().id, 'repo-B');
    const graph = runtimeGraph([
      { id: 'worker', agentName: 'Worker', scopeMode: 'PER_SCOPE', position: { x: 20, y: 30 } }
    ]);
    const run = {
      ...workflowRunDetail('run-new', 'RUNNING', [], 'Scoped Board', graph),
      repositoryIds: [repoA.id, repoB.id]
    };
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([repoA, repoB])),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const cards = dom.window.document.querySelectorAll('[data-execution-source-node-id="worker"]');
    expect(cards).toHaveLength(2);
    expect(dom.window.document.querySelectorAll('[data-execution-run-chip-id]')).toHaveLength(0);
    expect([...cards].every((card) => card.textContent?.includes('0 runs'))).toBe(true);
  });

  it('modern execution board never renders a raw repository ID when metadata is unavailable', async () => {
    const missingRepositoryId = '99999999-9999-4999-8999-999999999999';
    const graph = runtimeGraph([
      { id: 'worker', agentName: 'Worker', scopeMode: 'PER_SCOPE', position: { x: 20, y: 30 } }
    ]);
    const run = {
      ...workflowRunDetail('run-new', 'RUNNING', [], 'Scoped Board', graph),
      repositoryIds: [missingRepositoryId]
    };
    const fakeApi = api({
      listProjectRepositories: vi.fn(() => Promise.resolve([])),
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const card = dom.window.document.querySelector('[data-execution-source-node-id="worker"]')!;
    expect(card.textContent).toContain('Repository unavailable');
    expect(card.textContent).not.toContain(missingRepositoryId);
  });

  it('modern execution board renders forward edges as orthogonal paths from measured port anchors', async () => {
    const graph = runtimeGraph([
      {
        id: 'a',
        agentName: 'A',
        position: { x: 20, y: 30 },
        outputs: [
          { id: 'a-output-one', name: 'One', order: 0 },
          { id: 'a-output-two', name: 'Two', order: 1 }
        ]
      },
      {
        id: 'b',
        agentName: 'B',
        position: { x: 320, y: 30 },
        inputs: [
          { id: 'b-input-one', name: 'One', order: 0 },
          { id: 'b-input-two', name: 'Two', order: 1 }
        ]
      }
    ], [
      portConnection('edge-one', 'a-output-one', 'b-input-one'),
      portConnection('edge-two', 'a-output-two', 'b-input-two')
    ]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Modern Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    stubRect(dom.window.document.getElementById('agentsV2ExecutionCanvas'), 0, 0, 900, 600);
    page.taskExecutionView.viewport = { x: 0, y: 0, scale: 1 };
    stubAnchor(dom, 'a-output-one', 368, 100);
    stubAnchor(dom, 'b-input-one', 516, 120);
    stubAnchor(dom, 'a-output-two', 368, 140);
    stubAnchor(dom, 'b-input-two', 516, 160);
    page.taskExecutionView.renderModernEdges(page.taskExecutionView.modernProjection());

    const pathOne = dom.window.document.querySelector('[data-runtime-connection-id="edge-one"] path')?.getAttribute('d') || '';
    const pathTwo = dom.window.document.querySelector('[data-runtime-connection-id="edge-two"] path')?.getAttribute('d') || '';
    expect(pathOne).toMatch(/^M 368 100 .+ H 516$/);
    expect(pathTwo).toMatch(/^M 368 140 .+ H 516$/);
    expect(pathOne).not.toMatch(/[CS]/);
    expect(pathTwo).not.toMatch(/[CS]/);
    expect(pathOne).not.toBe(pathTwo);
    expect(routesSharePositiveLengthSegment(pathPoints(pathOne), pathPoints(pathTwo))).toBe(false);
  });

  it('bundles only exact shared-pin fan-out and fan-in routes', async () => {
    const graph = runtimeGraph([
      {
        id: 'source',
        agentName: 'Source',
        outputs: [
          { id: 'source-shared', name: 'Shared', order: 0 },
          { id: 'source-other', name: 'Other', order: 1 }
        ]
      },
      { id: 'left', agentName: 'Left' },
      { id: 'right', agentName: 'Right' },
      { id: 'third', agentName: 'Third' },
      { id: 'merge', agentName: 'Merge', inputs: [{ id: 'merge-shared', name: 'Shared', order: 0 }] }
    ], [
      portConnection('fanout-left', 'source-shared', 'left-input'),
      portConnection('fanout-right', 'source-shared', 'right-input'),
      portConnection('unrelated-third', 'source-other', 'third-input'),
      portConnection('fanin-left', 'left-output', 'merge-shared'),
      portConnection('fanin-right', 'right-output', 'merge-shared')
    ]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Bundled Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    const route = (id: string) => pathPoints(dom.window.document
      .querySelector(`[data-runtime-connection-id="${id}"] path`)?.getAttribute('d') || '');
    const fanoutLeft = route('fanout-left');
    const fanoutRight = route('fanout-right');
    const unrelated = route('unrelated-third');
    const faninLeft = route('fanin-left');
    const faninRight = route('fanin-right');

    expect(routesSharePositiveLengthSegment(fanoutLeft, fanoutRight)).toBe(true);
    expect(routesSharePositiveLengthSegment(faninLeft, faninRight)).toBe(true);
    expect(routesSharePositiveLengthSegment(fanoutLeft, unrelated)).toBe(false);
    expect(routesSharePositiveLengthSegment(fanoutRight, unrelated)).toBe(false);
    const projection = page.taskExecutionView.modernProjection();
    const projectedById = new Map<string, any>(projection.graph.connections.map((connection: any) => [connection.sourceConnectionId, connection]));
    const ids = ['fanout-left', 'fanout-right', 'unrelated-third', 'fanin-left', 'fanin-right'];
    for (let left = 0; left < ids.length; left += 1) {
      for (let right = left + 1; right < ids.length; right += 1) {
        const leftEdge = projectedById.get(ids[left]!)!;
        const rightEdge = projectedById.get(ids[right]!)!;
        if (!executionConnectionsMayBundle(leftEdge, rightEdge)) {
          expect(routesSharePositiveLengthSegment(route(ids[left]!), route(ids[right]!))).toBe(false);
        }
      }
    }
  });

  it('modern execution board routes reverse edges near the canvas origin outside node bounds without cubic curves', async () => {
    const graph = runtimeGraph([
      { id: 'worker', agentName: 'Worker', position: { x: 0, y: 0 }, inputs: [{ id: 'worker-feedback', name: 'Feedback', order: 0 }] },
      { id: 'reviewer', agentName: 'Reviewer', position: { x: 280, y: 0 }, outputs: [{ id: 'reviewer-fail', name: 'Fail', order: 0 }] }
    ], [
      portConnection('reviewer-worker', 'reviewer-fail', 'worker-feedback')
    ]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Modern Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    stubRect(dom.window.document.getElementById('agentsV2ExecutionCanvas'), 0, 0, 900, 600);
    page.taskExecutionView.viewport = { x: 0, y: 0, scale: 1 };
    const workerBounds = { left: 0, top: 0, right: MODERN_EXECUTION_CARD_WIDTH, bottom: 140 };
    const reviewerBounds = { left: 336, top: 0, right: 336 + MODERN_EXECUTION_CARD_WIDTH, bottom: 120 };
    stubRect(dom.window.document.querySelector('[data-execution-source-node-id="worker"]'), workerBounds.left, workerBounds.top, MODERN_EXECUTION_CARD_WIDTH, 140);
    stubRect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]'), reviewerBounds.left, reviewerBounds.top, MODERN_EXECUTION_CARD_WIDTH, 120);
    stubAnchor(dom, 'reviewer-fail', reviewerBounds.right, 40);
    stubAnchor(dom, 'worker-feedback', 0, 100);
    page.taskExecutionView.renderModernEdges(page.taskExecutionView.modernProjection());

    const path = dom.window.document.querySelector('[data-runtime-connection-id="reviewer-worker"] path')?.getAttribute('d') || '';
    expect(dom.window.document.querySelector('[data-runtime-connection-id="reviewer-worker"]')?.classList.contains('execution-edge-feedback-reentry')).toBe(true);
    expect(path).not.toMatch(/[CS]/);
    expect(path).toMatch(/^M (?:-?\d+(?:\.\d+)? )+-?\d+(?: H -?\d+(?:\.\d+)?| V -?\d+(?:\.\d+)?| Q -?\d+(?:\.\d+)? -?\d+(?:\.\d+)? -?\d+(?:\.\d+)? -?\d+(?:\.\d+)?)+$/);
    expectPathOutsideRects(path, [workerBounds, reviewerBounds]);
    expect(Math.min(...pathPoints(path).map((point) => point.y))).toBeLessThan(workerBounds.top);
  });

  it('renders a direct self-loop locally around its execution card', async () => {
    const graph = runtimeGraph([
      { id: 'reviewer', agentName: 'Reviewer' }
    ], [portConnection('reviewer-self', 'reviewer-output', 'reviewer-input')]);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'RUNNING', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'RUNNING', [], 'Self Loop Board', graph)))
    });
    const { dom, page } = await openedProject(fakeApi);
    await page.openTaskExecution('task-1');
    await flushAsync();

    const card = dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')!;
    stubRect(dom.window.document.getElementById('agentsV2ExecutionCanvas'), 0, 0, 900, 600);
    stubRect(card, 200, 100, MODERN_EXECUTION_CARD_WIDTH, 118);
    stubAnchor(dom, 'reviewer-input', 200, 140);
    stubAnchor(dom, 'reviewer-output', 488, 140);
    page.taskExecutionView.renderModernEdges(page.taskExecutionView.modernProjection());

    const edge = dom.window.document.querySelector('[data-runtime-connection-id="reviewer-self"]')!;
    const path = edge.querySelector('path')?.getAttribute('d') || '';
    const points = pathPoints(path);
    expect(edge.classList.contains('execution-edge-self-loop')).toBe(true);
    expect(Math.min(...points.map((point) => point.x))).toBeGreaterThanOrEqual(178);
    expect(Math.max(...points.map((point) => point.x))).toBeLessThanOrEqual(510);
    expect(Math.min(...points.map((point) => point.y))).toBeLessThan(100);
  });

  it('modern execution board keeps one card per source node for cycles and marks the latest selected output', async () => {
    const graph = runtimeGraph([
      { id: 'worker', agentName: 'Worker', position: { x: 20, y: 30 }, inputs: [{ id: 'worker-input', name: 'Input', order: 0 }, { id: 'worker-feedback', name: 'Feedback', order: 1 }] },
      { id: 'reviewer', agentName: 'Reviewer', position: { x: 300, y: 30 }, outputs: [{ id: 'reviewer-pass', name: 'Pass', order: 0 }, { id: 'reviewer-fail', name: 'Fail', order: 1 }] }
    ], [
      portConnection('worker-reviewer', 'worker-output', 'reviewer-input'),
      portConnection('reviewer-worker', 'reviewer-fail', 'worker-feedback')
    ]);
    const run = workflowRunDetail('run-new', 'SUCCEEDED', [
      modernNodeRun('worker-1', 'worker', 'SUCCEEDED', '2026-08-13T10:00:00Z', 'worker-output'),
      modernNodeRun('reviewer-1', 'reviewer', 'SUCCEEDED', '2026-08-13T10:01:00Z', 'reviewer-fail'),
      modernNodeRun('worker-2', 'worker', 'SUCCEEDED', '2026-08-13T10:02:00Z', 'worker-output'),
      modernNodeRun('reviewer-2', 'reviewer', 'SUCCEEDED', '2026-08-13T10:03:00Z', 'reviewer-pass')
    ], 'Cycle Board', graph);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'SUCCEEDED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    expect(dom.window.document.querySelectorAll('[data-execution-source-node-id="worker"]')).toHaveLength(1);
    expect(dom.window.document.querySelectorAll('[data-execution-source-node-id="reviewer"]')).toHaveLength(1);
    expect(dom.window.document.querySelector('[data-execution-source-node-id="worker"]')?.textContent).toContain('2 runs');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')?.textContent).toContain('#2 SUCCEEDED');
    expect(dom.window.document.querySelector('[data-runtime-port-id="reviewer-pass"]')?.classList.contains('selected')).toBe(true);
    expect(dom.window.document.querySelector('[data-runtime-port-id="reviewer-fail"]')?.classList.contains('selected')).toBe(false);
    expect(dom.window.document.querySelector('[data-runtime-connection-id="reviewer-worker"] path')?.getAttribute('d')).not.toMatch(/[CS]/);

    dom.window.document.querySelector<HTMLElement>('[data-execution-source-node-id="reviewer"]')?.click();
    const reviewerInvocation = dom.window.document.querySelector<HTMLSelectElement>('[data-node-run-invocation-select]')!;
    reviewerInvocation.value = 'reviewer-1';
    reviewerInvocation.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
    await flushAsync();
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('SUCCEEDED');
  });

  it('modern execution board shows terminal unconnected output selection without inventing an edge', async () => {
    const graph = runtimeGraph([
      { id: 'reviewer', agentName: 'Reviewer', position: { x: 20, y: 30 }, outputs: [{ id: 'continue', name: 'Continue', order: 0 }, { id: 'done', name: 'Done', order: 1 }] },
      { id: 'worker', agentName: 'Worker', position: { x: 300, y: 30 } }
    ], [
      portConnection('continue-worker', 'continue', 'worker-input')
    ]);
    const run = workflowRunDetail('run-new', 'SUCCEEDED', [
      modernNodeRun('reviewer-1', 'reviewer', 'SUCCEEDED', '2026-08-13T10:00:00Z', 'done')
    ], 'Terminal Board', graph);
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'SUCCEEDED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(run))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    expect(dom.window.document.querySelector('[data-runtime-port-id="done"]')?.classList.contains('selected')).toBe(true);
    expect(dom.window.document.querySelectorAll('[data-runtime-connection-id]')).toHaveLength(1);
    expect(dom.window.document.querySelector('[data-runtime-connection-id="continue-worker"]')).not.toBeNull();
  });

  it('Execution graph pans empty canvas, zooms around cursor, and keeps node positions read-only', async () => {
    const nodeRuns = [
      nodeRun('source-node', 'Planner', 'SUCCEEDED', [], 40, 50, { ok: true }),
      nodeRun('target-node', 'Reviewer', 'FAILED', ['source-node'], 340, 160, null, { code: 'ROUTING_FAILED', message: 'Output port was invalid.' })
    ];
    const fakeApi = api({
      getProjectTask: vi.fn(() => Promise.resolve(taskDetail('task-1', [taskRun('run-new', 'FAILED', '2026-08-13T10:00:00Z')]))),
      getWorkflowRun: vi.fn(() => Promise.resolve(workflowRunDetail('run-new', 'FAILED', nodeRuns)))
    });
    const { dom, page } = await openedProject(fakeApi);

    await page.openTaskExecution('task-1');
    await flushAsync();

    const canvas = dom.window.document.getElementById('agentsV2ExecutionCanvas')!;
    const nodesLayer = dom.window.document.getElementById('agentsV2ExecutionNodes')!;
    const edgesLayer = dom.window.document.getElementById('agentsV2ExecutionEdges')!;
    const targetNode = dom.window.document.querySelector<HTMLElement>('[data-execution-node-id="target-node"]')!;
    const initialNodeStyle = targetNode.getAttribute('style');

    canvas.dispatchEvent(new dom.window.MouseEvent('pointerdown', {
      bubbles: true,
      button: 0,
      clientX: 120,
      clientY: 140
    }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('pointermove', {
      bubbles: true,
      clientX: 170,
      clientY: 115
    }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('pointerup', { bubbles: true }));

    expect(nodesLayer.style.transform).toBe('translate(50px, -25px) scale(1)');
    expect(edgesLayer.style.transform).toBe(nodesLayer.style.transform);
    expect(targetNode.getAttribute('style')).toBe(initialNodeStyle);

    canvas.getBoundingClientRect = () => ({
      left: 20,
      top: 30,
      width: 800,
      height: 520,
      right: 820,
      bottom: 550,
      x: 20,
      y: 30,
      toJSON: () => ({})
    } as DOMRect);
    canvas.dispatchEvent(new dom.window.WheelEvent('wheel', {
      bubbles: true,
      cancelable: true,
      clientX: 220,
      clientY: 230,
      deltaY: -100
    }));

    expect(nodesLayer.style.transform).toBe('translate(38px, -43.00000000000003px) scale(1.08)');
    expect(edgesLayer.style.transform).toBe(nodesLayer.style.transform);
    expect(targetNode.getAttribute('style')).toBe(initialNodeStyle);

    targetNode.dispatchEvent(new dom.window.MouseEvent('pointerdown', {
      bubbles: true,
      button: 0,
      clientX: 360,
      clientY: 180
    }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('pointermove', {
      bubbles: true,
      clientX: 420,
      clientY: 240
    }));
    dom.window.document.dispatchEvent(new dom.window.MouseEvent('pointerup', { bubbles: true }));
    targetNode.click();
    await flushAsync();

    expect(nodesLayer.style.transform).toBe('translate(38px, -43.00000000000003px) scale(1.08)');
    expect(targetNode.getAttribute('style')).toBe(initialNodeStyle);
    expect(dom.window.document.getElementById('agentsV2NodeRunDetails')?.textContent).toContain('ROUTING_FAILED');
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

  it('New Task modal uses loaded repositories and sends ordered repositoryIds', async () => {
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
    const repositoryOptions = [...dom.window.document.querySelectorAll<HTMLInputElement>('#agentsV2TaskRepositories input')];
    expect(repositoryOptions.map((option) => option.parentElement?.textContent?.trim())).toEqual(['service-a']);
    const repositoryOption = taskRepositoryOption(dom.window.document);
    repositoryOption.checked = true;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).toHaveBeenCalledWith(project().id, {
      title: 'Test chain',
      input: 'Find X and pass the result forward',
      workflowId: 'wf-2',
      repositoryIds: [repositoryOption.value]
    });
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(1);
    expect(fakeApi.createWorkflowRun).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(false);
    expect(fakeApi.listProjectTasks).toHaveBeenCalledTimes(2);
    expect(dom.window.document.getElementById('agentsV2TasksList')?.textContent).toContain('Deploy Review');
  });

  it('renders task repositories alphabetically in one scrollable overflow-safe column', async () => {
    const longName = 'platform_graph_repository_with_a_name_that_must_wrap_inside_the_existing_modal_width';
    const repositories = [
      repository('cccccccc-cccc-4ccc-8ccc-cccccccccccc', project().id, 'zeta_service'),
      repository('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', project().id, longName),
      repository('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', project().id, 'ancestor_msgs')
    ];
    const fakeApi = api({ listProjectRepositories: vi.fn(() => Promise.resolve(repositories)) });
    const { dom } = await openedProject(fakeApi);
    dom.window.document.getElementById('agentsV2CreateTask')?.click();

    const selector = dom.window.document.getElementById('agentsV2TaskRepositories') as HTMLElement;
    const options = [...selector.querySelectorAll<HTMLInputElement>('input[name="repositoryIds"]')];
    expect(options.map((option) => option.parentElement?.textContent?.trim())).toEqual([
      'ancestor_msgs',
      longName,
      'zeta_service'
    ]);
    options.forEach((option) => {
      option.click();
      expect(option.checked).toBe(true);
    });

    const css = readFileSync(join(process.cwd(), 'src', 'operator', 'operator-ui.css'), 'utf8');
    expect(css).toMatch(/\.agents-v2-checkbox-list\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(0, 1fr\);[^}]*max-height:\s*300px;[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*auto;/s);
    expect(css).toMatch(/\.agents-v2-checkbox-option\s*\{[^}]*grid-template-columns:\s*auto minmax\(0, 1fr\);[^}]*min-width:\s*0;/s);
    expect(css).toMatch(/\.agents-v2-checkbox-option span\s*\{[^}]*min-width:\s*0;[^}]*overflow-wrap:\s*anywhere;/s);
  });

  it('does not submit a Task without a selected repository', async () => {
    const { dom, fakeApi } = await openedProject();
    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = 'Check calculation';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Count letters.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;

    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2TaskModalError')?.textContent).toContain('select at least one repository');
  });

  it('submits multiple repository IDs in displayed Project repository order including uncloned repositories', async () => {
    const repoB = repository('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', project().id, 'service-b', true);
    const repoA = repository('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', project().id, 'service-a', false);
    const fakeApi = api({ listProjectRepositories: vi.fn(() => Promise.resolve([repoB, repoA])) });
    const { dom } = await openedProject(fakeApi);
    dom.window.document.getElementById('agentsV2CreateTask')?.click();

    const first = taskRepositoryOption(dom.window.document, 0);
    const second = taskRepositoryOption(dom.window.document, 1);
    expect([first.parentElement?.textContent?.trim(), second.parentElement?.textContent?.trim()]).toEqual(['service-a', 'service-b']);
    expect(repoA.cloned).toBe(false);
    second.checked = true;
    first.checked = true;
    (dom.window.document.getElementById('agentsV2TaskTitle') as HTMLInputElement).value = 'Cross-service task';
    (dom.window.document.getElementById('agentsV2TaskInput') as HTMLTextAreaElement).value = 'Update both services.';
    (dom.window.document.getElementById('agentsV2TaskWorkflow') as HTMLSelectElement).value = workflow().id;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).toHaveBeenCalledWith(project().id, {
      title: 'Cross-service task',
      input: 'Update both services.',
      workflowId: workflow().id,
      repositoryIds: [repoB.id, repoA.id]
    });
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(1);
  });

  it('keeps New Task disabled while repositories load and cannot open the modal', async () => {
    const repositories = deferred<any>();
    const fakeApi = api({ listProjectRepositories: vi.fn(() => repositories.promise) });
    const context = await mountedPage(fakeApi);
    const opening = context.page.openProject(project().id);
    await flushAsync();

    const newTask = context.dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement;
    expect(newTask.disabled).toBe(true);
    newTask.click();
    expect(context.dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(false);

    repositories.resolve([repository()]);
    await opening;
  });

  it('keeps New Task disabled when repository loading fails or returns no repositories', async () => {
    const failed = await openedProject(api({ listProjectRepositories: vi.fn(() => Promise.reject(new Error('Repositories unavailable'))) }));
    expect((failed.dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(true);
    failed.page.openTaskModal();
    expect(failed.dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(false);

    const empty = await openedProject(api({ listProjectRepositories: vi.fn(() => Promise.resolve([])) }));
    expect((empty.dom.window.document.getElementById('agentsV2CreateTask') as HTMLButtonElement).disabled).toBe(true);
    empty.page.openTaskModal();
    expect(empty.dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(false);
  });

  it('opens New Task normally when repository, workflow, and task state are current', async () => {
    const { dom, page, fakeApi } = await openedProject();
    expect(page.canCreateTask()).toBe(true);
    dom.window.document.getElementById('agentsV2CreateTask')?.click();
    expect(dom.window.document.getElementById('agentsV2TaskDialog')?.hasAttribute('open')).toBe(true);
    expect(fakeApi.listProjectRepositories).toHaveBeenCalledTimes(1);
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
    (dom.window.document.querySelector('#agentsV2TaskRepositories input') as HTMLInputElement).checked = true;
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
    (dom.window.document.querySelector('#agentsV2TaskRepositories input') as HTMLInputElement).checked = true;
    dom.window.document.getElementById('agentsV2TaskForm')?.dispatchEvent(new dom.window.Event('submit'));
    await flushAsync();

    expect(fakeApi.createProjectTask).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2TaskModalError')?.textContent).toContain('Enter a title, task, workflow');
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
    (dom.window.document.querySelector('#agentsV2TaskRepositories input') as HTMLInputElement).checked = true;
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
    setRandomUuids(dom, [
      '11111111-1111-4111-8111-111111111111',
      '11111111-1111-4111-8111-111111111112',
      '11111111-1111-4111-8111-111111111113',
      '22222222-2222-4222-8222-222222222222',
      '22222222-2222-4222-8222-222222222223',
      '22222222-2222-4222-8222-222222222224',
      '33333333-3333-4333-8333-333333333333',
      '33333333-3333-4333-8333-333333333334',
      '33333333-3333-4333-8333-333333333335'
    ]);

    page.workflowBuilder.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    page.workflowBuilder.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    page.workflowBuilder.addNode('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');

    expect(page.workflowBuilder.workflow.nodes).toHaveLength(3);
    expect(page.workflowBuilder.workflow.nodes[0].id).not.toBe(page.workflowBuilder.workflow.nodes[1].id);
    expect(page.workflowBuilder.workflow.nodes[0].targetId).toBe(page.workflowBuilder.workflow.nodes[1].targetId);
    expect(page.workflowBuilder.workflow.nodes.map((node: any) => node.position)).toEqual([
      { x: 120, y: 90 },
      { x: 480, y: 90 },
      { x: 840, y: 90 }
    ]);
    for (const [left, right] of [
      [page.workflowBuilder.workflow.nodes[0], page.workflowBuilder.workflow.nodes[1]],
      [page.workflowBuilder.workflow.nodes[1], page.workflowBuilder.workflow.nodes[2]]
    ] as any[]) {
      expect(right.position.x - left.position.x).toBeGreaterThanOrEqual(252);
    }
    expect(page.workflowBuilder.workflow.nodes[0].inputs).toEqual([
      { id: '11111111-1111-4111-8111-111111111112', name: 'Input', description: 'Default workflow input.', order: 0 }
    ]);
    expect(page.workflowBuilder.workflow.nodes[0].outputs).toEqual([
      { id: '11111111-1111-4111-8111-111111111113', name: 'Output', description: 'Default workflow output.', order: 0 }
    ]);
  });

  it('Node body drag changes position without rebuilding Node DOM', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20)]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    const element = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-1"]')!;

    element.dispatchEvent(pointer(dom, 'pointerdown', 10, 20));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 60, 80));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 60, 80));

    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 60, y: 80 });
    expect(dom.window.document.querySelector('[data-node-id="node-1"]')).toBe(element);
    expect(dom.window.document.getElementById('agentsV2NodeEditorDialog')?.hasAttribute('open')).toBe(false);
  });

  it('compact Node renders configured ports as in-card labels with edge handles', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-a', name: 'Review feedback', description: 'Feedback.', order: 0 },
        { id: 'input-b', name: 'Context', description: 'Context.', order: 1 },
        { id: 'input-c', name: 'Test result', description: 'Test.', order: 2 },
        { id: 'input-d', name: 'Extra notes', description: 'Notes.', order: 3 }
      ], [
        { id: 'output-a', name: 'Approved', description: 'Continue.', order: 0 },
        { id: 'output-b', name: 'Return', description: 'Return.', order: 1 },
        { id: 'output-c', name: 'Reject', description: 'Reject.', order: 2 }
      ]),
      node('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20)
    ]))) });
    const { dom } = await openedBuilder(fakeApi);
    const source = consoleSourceText();
    const nodeElement = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-1"]')!;
    const noPortNode = dom.window.document.querySelector<HTMLElement>('[data-node-id="node-2"]')!;

    expect(source).toContain('const NODE_WIDTH = 252;');
    expect(source).toMatch(/\.workflow-node\s*\{[\s\S]*width: 252px;/);
    expect(source).toMatch(/\.workflow-node\s*\{[\s\S]*min-height: max\(132px, calc\(var\(--workflow-node-port-rows, 1\) \* 26px \+ 58px\)\);/);
    expect(source).toMatch(/\.workflow-node-port-list\.input\s*\{[\s\S]*left: 0;/);
    expect(source).toMatch(/\.workflow-node-port-list\.output\s*\{[\s\S]*right: 0;/);
    expect(source).toMatch(/\.node-handle\.input\s*\{[\s\S]*margin-left: -9px;/);
    expect(source).toMatch(/\.node-handle\.output\s*\{[\s\S]*margin-right: -9px;/);
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
    expect(nodeElement.querySelectorAll('[data-node-input-port]')).toHaveLength(4);
    expect(nodeElement.querySelectorAll('[data-node-output-port]')).toHaveLength(3);
    expect(noPortNode.querySelectorAll('[data-node-input-port], [data-node-output-port]')).toHaveLength(0);
  });

  it('Canvas bounds use current Node geometry for Nodes with many Ports', async () => {
    const manyInputs = Array.from({ length: 40 }, (_value, index) => ({
      id: `input-${index}`,
      name: `Input ${index}`,
      description: `Input ${index}.`,
      order: index
    }));
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 20, 900, 'DEPENDENCIES_ONLY', manyInputs, [])
    ]))) });
    const { dom } = await openedBuilder(fakeApi);
    const svg = dom.window.document.getElementById('agentsV2WorkflowEdges')!;

    expect(Number(svg.getAttribute('height'))).toBeGreaterThanOrEqual(900 + (40 * 26 + 58) + 240);
  });

  it('Workflow drawing layers resize with the visible Canvas', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf'))) });
    const { dom } = await openedBuilder(fakeApi);
    const canvas = dom.window.document.getElementById('agentsV2WorkflowCanvas')!;
    const svg = dom.window.document.getElementById('agentsV2WorkflowEdges')!;
    const nodesLayer = dom.window.document.getElementById('agentsV2WorkflowNodes')!;
    Object.defineProperty(canvas, 'clientWidth', { configurable: true, value: 1900 });
    Object.defineProperty(canvas, 'clientHeight', { configurable: true, value: 1200 });

    dom.window.dispatchEvent(new dom.window.Event('resize'));

    expect(svg.getAttribute('width')).toBe('1900');
    expect(svg.getAttribute('height')).toBe('1200');
    expect(nodesLayer.style.width).toBe('1900px');
    expect(nodesLayer.style.height).toBe('1200px');
  });

  it('clicking compact Node opens Node Editor and Cancel leaves draft unchanged', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [], [
        { id: 'out-1', name: 'Approved', description: 'Accepted.', order: 0 }
      ])
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    setRandomUuids(dom, ['a-b-connection', 'a-c-connection', 'b-c-connection']);

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
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [
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

  it('Node Editor rejects deletion of connected source Output and target Input Ports', async () => {
    const graph = workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-a', name: 'Input', description: 'Default workflow input.', order: 0 }
      ], [
        { id: 'output-a', name: 'Output', description: 'Default workflow output.', order: 0 }
      ]),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-b', name: 'Input', description: 'Default workflow input.', order: 0 }
      ], [
        { id: 'output-b', name: 'Output', description: 'Default workflow output.', order: 0 }
      ])
    ], project().id, [portConnection('edge-a-b', 'output-a', 'input-b')]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);

    clickNode(dom, 'a');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="output-a"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent)
      .toContain('Port is connected. Remove its connections first.');
    expect(editorRows(dom, 'outputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['output-a']);
    expect(page.workflowBuilder.workflow.connections).toEqual([portConnection('edge-a-b', 'output-a', 'input-b')]);
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    clickNode(dom, 'b');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="input-b"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent)
      .toContain('Port is connected. Remove its connections first.');
    expect(editorRows(dom, 'inputs').map((row) => row.dataset.nodeEditorPort)).toEqual(['input-b']);
    expect(page.workflowBuilder.workflow.connections).toEqual([portConnection('edge-a-b', 'output-a', 'input-b')]);
  });

  it('Port rename preserves existing WorkflowConnection identity and Port IDs', async () => {
    const graph = workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [], [
        { id: 'output-1', name: 'Output', description: 'Default workflow output.', order: 0 }
      ]),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20, 'DEPENDENCIES_ONLY', [
        { id: 'input-1', name: 'Input', description: 'Default workflow input.', order: 0 }
      ], [])
    ], project().id, [portConnection('connection-1', 'output-1', 'input-1')]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);

    clickNode(dom, 'a');
    editPort(dom, 'outputs', 'output-1');
    setPortFields(editorRow(dom, 'outputs', 0), 'Approved', 'Approved output.');
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();

    expect(page.workflowBuilder.workflow.nodes[0].outputs).toEqual([
      { id: 'output-1', name: 'Approved', description: 'Approved output.', order: 0 }
    ]);
    expect(page.workflowBuilder.workflow.connections).toEqual([
      portConnection('connection-1', 'output-1', 'input-1')
    ]);
  });

  it('Canvas supports multiple distinct Port edges between the same Nodes', async () => {
    const graph = workflow('wf', [
      node('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [], [
        { id: 'a-output-1', name: 'Output1', description: 'First output.', order: 0 },
        { id: 'a-output-2', name: 'Output2', description: 'Second output.', order: 1 }
      ]),
      node('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20, 'DEPENDENCIES_ONLY', [
        { id: 'b-input-1', name: 'Input1', description: 'First input.', order: 0 },
        { id: 'b-input-2', name: 'Input2', description: 'Second input.', order: 1 }
      ], [])
    ]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);
    setRandomUuids(dom, ['edge-1', 'edge-2']);

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="a-output-1"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 200, 40));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="b-input-1"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 20, 40));
    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="a-output-2"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 200, 66));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="b-input-2"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 20, 66));

    expect(page.workflowBuilder.workflow.connections).toEqual([
      portConnection('edge-1', 'a-output-1', 'b-input-1'),
      portConnection('edge-2', 'a-output-2', 'b-input-2')
    ]);
    expect(new Set(page.workflowBuilder.workflow.connections.map((item: any) => item.id))).toEqual(new Set(['edge-1', 'edge-2']));
    expect(dom.window.document.querySelectorAll('.workflow-edge')).toHaveLength(2);
  });

  it('Node Editor switches and clears the single active port editor without losing draft values', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20, 'DEPENDENCIES_ONLY', [], [
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
        node('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 44, 55, 'DEPENDENCIES_ONLY', [
          { id: 'input-b', name: 'Second input', description: 'Second.', order: 1 },
          { id: 'input-a', name: 'First input', description: 'First.', order: 0 }
        ], [
          { id: 'output-b', name: 'Second output', description: 'Second.', order: 1 },
          { id: 'output-a', name: 'First output', description: 'First.', order: 0 }
        ])
      ], project().id, [], 'input-a', 'output-a'))),
      updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes, project().id, request.connections, request.taskInputPortId, request.taskOutputPortId)))
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
        inputMode: 'DEPENDENCIES_ONLY',
        scopeMode: 'GLOBAL',
        inputs: [
          { id: 'input-a', name: 'First input', description: 'First.', order: 0 },
          { id: 'input-b', name: 'Second input', description: 'Second.', order: 1 }
        ],
        outputs: [
          { id: 'output-a', name: 'First output', description: 'First.', order: 0 },
          { id: 'output-b', name: 'Second output', description: 'Second.', order: 1 }
        ],
        position: { x: 44, y: 55 }
      }],
      connections: [],
      taskInputPortId: 'input-a',
      taskOutputPortId: 'output-a'
    });
    expect(portTexts(dom, 'node-1', 'input')).toEqual(['First input', 'Second input']);
    expect(portTexts(dom, 'node-1', 'output')).toEqual(['First output', 'Second output']);
  });

  it('Node body drag updates only connected edge geometry without rebuilding unrelated edge DOM', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 10, 20),
      portedNode('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20),
      portedNode('c', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 10, 220),
      portedNode('d', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 260, 220)
    ], project().id, [connection('a', 'b'), connection('c', 'd')]))) });
    const { dom } = await openedBuilder(fakeApi);
    const movingEdge = dom.window.document.querySelector<SVGGElement>('[data-edge-id="a-b-connection"]')!;
    const unrelatedEdge = dom.window.document.querySelector<SVGGElement>('[data-edge-id="c-d-connection"]')!;
    const movingPathBefore = movingEdge.querySelector('.edge-visible')!.getAttribute('d');
    const unrelatedPathBefore = unrelatedEdge.querySelector('.edge-visible')!.getAttribute('d');
    const nodeElement = dom.window.document.querySelector<HTMLElement>('[data-node-id="a"]')!;

    nodeElement.dispatchEvent(pointer(dom, 'pointerdown', 10, 20));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 60, 80));

    const movingEdgeAfter = dom.window.document.querySelector<SVGGElement>('[data-edge-id="a-b-connection"]')!;
    const unrelatedEdgeAfter = dom.window.document.querySelector<SVGGElement>('[data-edge-id="c-d-connection"]')!;
    expect(movingEdgeAfter).toBe(movingEdge);
    expect(movingEdgeAfter.querySelector('.edge-visible')!.getAttribute('d')).not.toBe(movingPathBefore);
    expect(unrelatedEdgeAfter).toBe(unrelatedEdge);
    expect(unrelatedEdgeAfter.querySelector('.edge-visible')!.getAttribute('d')).toBe(unrelatedPathBefore);

    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 1800, 1200));

    const expandedSvg = dom.window.document.getElementById('agentsV2WorkflowEdges')!;
    expect(Number(expandedSvg.getAttribute('width'))).toBeGreaterThan(1600);
    expect(Number(expandedSvg.getAttribute('height'))).toBeGreaterThan(1000);
    expect(dom.window.document.querySelector<SVGGElement>('[data-edge-id="a-b-connection"]')).toBe(movingEdge);
    expect(movingEdge.querySelector('.edge-visible')!.getAttribute('d')).toMatch(/^M /);
    expect(movingEdge.querySelector('.edge-visible')!.getAttribute('d')).toContain('2052 1255');
  });

  it('Canvas background drag pans the viewport and wheel zooms without moving Nodes', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 44, 55)
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    const canvas = dom.window.document.getElementById('agentsV2WorkflowCanvas')!;
    const nodesLayer = dom.window.document.getElementById('agentsV2WorkflowNodes')!;

    canvas.dispatchEvent(pointer(dom, 'pointerdown', 100, 100));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 140, 125));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 140, 125));

    expect(page.workflowBuilder.viewport).toEqual({ x: 40, y: 25, scale: 1 });
    expect(nodesLayer.style.transform).toBe('translate(40px, 25px) scale(1)');
    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 44, y: 55 });

    canvas.dispatchEvent(wheel(dom, 100, 100, -100));

    expect(page.workflowBuilder.viewport.scale).toBeGreaterThan(1);
    expect(nodesLayer.style.transform).toContain('scale(1.08)');
    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 44, y: 55 });
  });

  it('dragging from output handle previews and dropping on target input creates port connection', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    setRandomUuids(dom, ['node-1-node-2-new-connection']);

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="node-1-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 300, 120));
    const previewPath = dom.window.document.querySelector('.workflow-edge-preview')?.getAttribute('d') || '';
    expect(previewPath).toContain(' Q ');
    expect(previewPath).toContain(' H 300');
    expect(previewPath).not.toContain(' C ');

    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="node-2-input"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.connections).toEqual([{
      id: 'node-1-node-2-new-connection',
      sourceOutputPortId: 'node-1-output',
      targetInputPortId: 'node-2-input'
    }]);
    expect(dom.window.document.querySelector('.workflow-edge-preview')).toBeNull();
    expect(dom.window.document.getElementById('agentsV2WorkflowEdges')?.innerHTML).toContain('workflow-edge');
  });

  it('allows a warned self-loop only when the same input has an external dependency', async () => {
    const graph = workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('implementer', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ], project().id, [connection('implementer', 'reviewer')]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);
    const confirm = vi.spyOn(dom.window, 'confirm').mockReturnValue(true);
    setRandomUuids(dom, ['reviewer-self-connection']);

    expect(page.workflowBuilder.canConnect('reviewer-output', 'reviewer-input')).toBe(true);
    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="reviewer-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="reviewer-input"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('non-terminating workflow'));
    expect(page.workflowBuilder.workflow.connections).toContainEqual({
      id: 'reviewer-self-connection',
      sourceOutputPortId: 'reviewer-output',
      targetInputPortId: 'reviewer-input'
    });
  });

  it('disables removal of the last external dependency guarding a self-loop', async () => {
    const graph = workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('implementer', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ], project().id, [
      connection('implementer', 'reviewer'),
      connection('reviewer', 'reviewer', 'reviewer-self-connection')
    ]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);
    const protectedControl = dom.window.document.querySelector<SVGCircleElement>('[data-edge-id="implementer-reviewer-connection"] .edge-remove')!;

    expect(protectedControl.classList.contains('disabled')).toBe(true);
    expect(protectedControl.getAttribute('aria-disabled')).toBe('true');
    expect(protectedControl.hasAttribute('data-remove-connection')).toBe(false);
    page.workflowBuilder.removeConnection('implementer-reviewer-connection');
    expect(page.workflowBuilder.workflow.connections).toHaveLength(2);

    page.workflowBuilder.removeConnection('reviewer-self-connection');
    expect(page.workflowBuilder.workflow.connections).toEqual([connection('implementer', 'reviewer')]);
  });

  it('does not remove the source node of the last external dependency guarding a self-loop', async () => {
    const graph = workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('implementer', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ], project().id, [
      connection('implementer', 'reviewer'),
      connection('reviewer', 'reviewer', 'reviewer-self-connection')
    ]);
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);
    const originalGraph = structuredClone(page.workflowBuilder.workflow);

    page.workflowBuilder.removeNode('implementer');

    expect(page.workflowBuilder.workflow).toEqual(originalGraph);
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent)
      .toContain('Remove the dependent self-loop');

    page.workflowBuilder.removeConnection('reviewer-self-connection');
    page.workflowBuilder.removeNode('implementer');

    expect(page.workflowBuilder.workflow.nodes.map((candidate: any) => candidate.id)).toEqual(['reviewer']);
    expect(page.workflowBuilder.workflow.connections).toEqual([]);
  });

  it('rejects an unguarded self-loop before showing a warning', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);
    const confirm = vi.spyOn(dom.window, 'confirm');

    expect(page.workflowBuilder.canConnect('reviewer-output', 'reviewer-input')).toBe(false);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('routes Workflow Builder feedback edges through the shortest path outside Node bounds', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 70, 95),
      portedNode('plus', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 82, 272)
    ], project().id, [connection('reviewer', 'plus')]))) });
    const { dom } = await openedBuilder(fakeApi);

    const path = dom.window.document.querySelector<SVGPathElement>('[data-edge-id="reviewer-plus-connection"] .edge-visible')?.getAttribute('d') || '';
    const remove = dom.window.document.querySelector<SVGCircleElement>('[data-edge-id="reviewer-plus-connection"] .edge-remove')!;

    expect(path).not.toContain(' C ');
    expect(path).toContain(' Q ');
    expect(path).toContain('V 424');
    expect(path).toContain('Q 50 436');
    expect(remove.getAttribute('cy')).toBe('436');
    expect(remove.getAttribute('cx')).not.toBe(String((322 + 82) / 2));
  });

  it('keeps all edge delete controls on route midpoints while connected nodes move', async () => {
    const graph = workflow('wf', [
      portedNode('reviewer', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 70, 95),
      portedNode('plus', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 82, 272)
    ], project().id, [connection('reviewer', 'plus')], 'plus-input', 'reviewer-output');
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(graph)) });
    const { dom, page } = await openedBuilder(fakeApi);
    const builder: any = page.workflowBuilder;

    const expectControlAtRouteMidpoint = (selector: string, route: any[]) => {
      const expected = builder.routeMidpoint(route);
      const control = dom.window.document.querySelector<SVGCircleElement>(`${selector} .edge-remove`)!;
      expect(Number(control.getAttribute('cx'))).toBeCloseTo(expected.x);
      expect(Number(control.getAttribute('cy'))).toBeCloseTo(expected.y);
    };

    dom.window.document.querySelector<HTMLElement>('[data-node-id="reviewer"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 80, 105));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 420, 180));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 420, 180));

    let source = builder.portById('reviewer-output', 'outputs');
    let target = builder.portById('plus-input', 'inputs');
    expectControlAtRouteMidpoint('[data-edge-id="reviewer-plus-connection"]', builder.edgeRoute(source, target));
    expectControlAtRouteMidpoint('[data-task-output-edge]', builder.taskOutputRoute(source));

    dom.window.document.querySelector<HTMLElement>('[data-node-id="plus"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 90, 282));
    dom.window.document.dispatchEvent(pointer(dom, 'pointermove', 140, 520));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 140, 520));

    source = builder.portById('reviewer-output', 'outputs');
    target = builder.portById('plus-input', 'inputs');
    const bentRoute = builder.edgeRoute(source, target);
    expect(bentRoute.length).toBeGreaterThan(2);
    expectControlAtRouteMidpoint('[data-edge-id="reviewer-plus-connection"]', bentRoute);
    expectControlAtRouteMidpoint('[data-task-input-edge]', builder.taskInputRoute(target));
  });

  it('Task Input loads, targets the same input as feedback, reconnects, saves, and disconnects', async () => {
    const graph = workflow('wf', [
      portedNode('plus', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('reviewer', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20)
    ], project().id, [portConnection('reviewer-plus-feedback', 'reviewer-output', 'plus-input')], 'plus-input', 'plus-output');
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(graph)),
      updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes, project().id, request.connections, request.taskInputPortId, request.taskOutputPortId)))
    });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(dom.window.document.querySelector('[data-task-input]')?.textContent).toContain('TASK INPUT');
    expect(dom.window.document.querySelector('[data-task-input-edge]')).not.toBeNull();
    expect(dom.window.document.querySelector('[data-edge-id="reviewer-plus-feedback"]')).not.toBeNull();
    expect(page.workflowBuilder.workflow.taskInputPortId).toBe('plus-input');
    expect(page.workflowBuilder.workflow.connections).toEqual([
      portConnection('reviewer-plus-feedback', 'reviewer-output', 'plus-input')
    ]);

    clickNode(dom, 'plus');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="plus-input"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent)
      .toContain('Port is connected. Remove its connections first.');
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    dom.window.document.querySelector<HTMLElement>('[data-task-input-output]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 130, 64));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="reviewer-input"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 260, 72));

    expect(page.workflowBuilder.workflow.taskInputPortId).toBe('reviewer-input');
    expect(page.workflowBuilder.workflow.connections).toEqual([
      portConnection('reviewer-plus-feedback', 'reviewer-output', 'plus-input')
    ]);
    await page.workflowBuilder.save();
    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', expect.objectContaining({
      taskInputPortId: 'reviewer-input',
      taskOutputPortId: 'plus-output',
      connections: [portConnection('reviewer-plus-feedback', 'reviewer-output', 'plus-input')]
    }));

    dom.window.document.querySelector<SVGElement>('[data-remove-task-input]')!
      .dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    expect(page.workflowBuilder.workflow.taskInputPortId).toBeNull();
    expect(dom.window.document.querySelector('[data-task-input-edge]')).toBeNull();
  });

  it('Task Output loads, connects, reconnects, saves, blocks port deletion, and disconnects', async () => {
    const graph = workflow('wf', [
      portedNode('plus', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('reviewer', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20)
    ], project().id, [], 'plus-input', 'plus-output');
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(graph)),
      updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(_workflowId, request.nodes, project().id, request.connections, request.taskInputPortId, request.taskOutputPortId)))
    });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(dom.window.document.querySelector('[data-task-output]')?.textContent).toContain('TASK OUTPUT');
    expect(dom.window.document.querySelector('[data-task-output-edge]')).not.toBeNull();
    expect(page.workflowBuilder.workflow.taskOutputPortId).toBe('plus-output');
    expect(page.workflowBuilder.workflow.connections).toEqual([]);

    clickNode(dom, 'plus');
    dom.window.document.querySelector<HTMLElement>('[data-node-editor-remove="plus-output"]')?.click();
    expect(dom.window.document.getElementById('agentsV2NodeEditorError')?.textContent)
      .toContain('Port is connected. Remove its connections first.');
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="reviewer-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 464, 72));
    dom.window.document.querySelector<HTMLElement>('[data-task-output-input]')!
      .dispatchEvent(pointer(dom, 'pointerup', 1360, 64));

    expect(page.workflowBuilder.workflow.taskOutputPortId).toBe('reviewer-output');
    expect(page.workflowBuilder.workflow.connections).toEqual([]);
    await page.workflowBuilder.save();
    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', expect.objectContaining({
      taskInputPortId: 'plus-input',
      taskOutputPortId: 'reviewer-output',
      connections: []
    }));

    dom.window.document.querySelector<SVGElement>('[data-remove-task-output]')!
      .dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    expect(page.workflowBuilder.workflow.taskOutputPortId).toBeNull();
    expect(dom.window.document.querySelector('[data-task-output-edge]')).toBeNull();
  });

  it('save rejects non-empty Workflow without Task Input before submitting', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
        portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
      ]))),
      updateWorkflow: vi.fn()
    });
    const { dom, page } = await openedBuilder(fakeApi);

    await page.workflowBuilder.save();

    expect(fakeApi.updateWorkflow).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent)
      .toContain('Task Input is required before saving this workflow.');
  });

  it('save rejects non-empty Workflow without Task Output before submitting', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
        portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
      ], project().id, [], 'node-1-input'))),
      updateWorkflow: vi.fn()
    });
    const { dom, page } = await openedBuilder(fakeApi);

    await page.workflowBuilder.save();

    expect(fakeApi.updateWorkflow).not.toHaveBeenCalled();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent)
      .toContain('Task Output is required before saving this workflow.');
  });

  it('dropping connection on empty canvas cancels', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="node-1-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.dispatchEvent(pointer(dom, 'pointerup', 500, 500));

    expect(page.workflowBuilder.workflow.connections).toEqual([]);
    expect(dom.window.document.querySelector('.workflow-edge-preview')).toBeNull();
  });

  it('self and duplicate connections are rejected locally', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
    ], project().id, [connection('node-1', 'node-2')]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="node-1-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="node-1-input"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.connections).toEqual([{
      id: 'node-1-node-2-connection',
      sourceOutputPortId: 'node-1-output',
      targetInputPortId: 'node-2-input'
    }]);

    dom.window.document.querySelector<HTMLElement>('[data-node-output-port="node-1-output"]')!
      .dispatchEvent(pointer(dom, 'pointerdown', 214, 72));
    dom.window.document.querySelector<HTMLElement>('[data-node-input-port="node-2-input"]')!
      .dispatchEvent(pointer(dom, 'pointerup', 10, 72));
    expect(page.workflowBuilder.workflow.connections).toHaveLength(1);
  });

  it('fan-out and fan-in connections are stored as port connections', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
      portedNode('c', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    ]))) });
    const { dom, page } = await openedBuilder(fakeApi);

    dragConnect(dom, 'a', 'b');
    dragConnect(dom, 'a', 'c');
    dragConnect(dom, 'b', 'c');

    expect(page.workflowBuilder.workflow.connections.map((connection: any) => [
      connection.sourceOutputPortId,
      connection.targetInputPortId
    ])).toEqual([
      ['a-output', 'b-input'],
      ['a-output', 'c-input'],
      ['b-output', 'c-input']
    ]);
  });

  it('existing edge can be removed directly, saved, and Node removal cleans references', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('a', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('b', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
      ], project().id, [connection('a', 'b')], 'a-input', 'b-output'))),
      updateWorkflow: vi.fn((_workflowId: string, request: any) => Promise.resolve(workflow(
        _workflowId,
        request.nodes,
        project().id,
        request.connections,
        request.taskInputPortId,
        request.taskOutputPortId
      )))
    });
    const { dom, page } = await openedBuilder(fakeApi);
    const source = consoleSourceText();

    expect(source).toMatch(/\.workflow-nodes\s*\{[^}]*pointer-events: none;/);
    expect(source).toMatch(/\.workflow-node,[\s\S]*\.workflow-task-input,[\s\S]*\.workflow-task-output\s*\{[^}]*pointer-events: auto;/);
    expect(source).toMatch(/\.agents-v2-builder\s*\{[^}]*height: calc\(100dvh - 80px\);/);
    expect(source).toMatch(/\.workflow-builder-body\s*\{[^}]*grid-row: 3;[^}]*min-height: 0;/);
    expect(source).toMatch(/\.workflow-canvas\s*\{[^}]*min-height: 0;/);

    dom.window.document.querySelector<SVGElement>('[data-remove-connection="a-b-connection"]')!
      .dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    expect(page.workflowBuilder.workflow.connections).toEqual([]);
    await page.workflowBuilder.save();
    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', expect.objectContaining({ connections: [] }));
    setRandomUuids(dom, ['a-b-new-connection']);
    dragConnect(dom, 'a', 'b');
    page.workflowBuilder.removeNode('a');
    expect(page.workflowBuilder.workflow.nodes).toHaveLength(1);
    expect(page.workflowBuilder.workflow.connections).toEqual([]);
  });

  it('save submits complete graph, reload restores positions, and backend cycle errors are shown', async () => {
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
        portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 44, 55)
      ], project().id, [], 'node-1-input', 'node-1-output'))),
      updateWorkflow: vi.fn()
        .mockResolvedValueOnce(workflow('wf', [
          portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 80, 90)
        ], project().id, [], 'node-1-input', 'node-1-output'))
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
        inputMode: 'DEPENDENCIES_ONLY',
        scopeMode: 'GLOBAL',
        inputs: [{ id: 'node-1-input', name: 'Input', description: 'Default workflow input.', order: 0 }],
        outputs: [{ id: 'node-1-output', name: 'Output', description: 'Default workflow output.', order: 0 }],
        position: { x: 80, y: 90 }
      }],
      connections: [],
      taskInputPortId: 'node-1-input',
      taskOutputPortId: 'node-1-output'
    });
    expect(page.workflowBuilder.workflow.nodes[0].position).toEqual({ x: 80, y: 90 });

    await page.workflowBuilder.save();
    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent).toContain('WORKFLOW_GRAPH_CYCLE');
  });

  it('save displays backend inconsistent workflow graph messages', async () => {
    const inconsistencyMessage = 'Workflow contains nodes that are not reachable from Task Input. Connect all workflow nodes to the execution flow or remove them.';
    const fakeApi = api({
      getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
        portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 44, 55)
      ], project().id, [], 'node-1-input', 'node-1-output'))),
      updateWorkflow: vi.fn(() => Promise.reject(Object.assign(new Error(inconsistencyMessage), {
        code: 'INCONSISTENT_WORKFLOW_GRAPH'
      })))
    });
    const { dom, page } = await openedBuilder(fakeApi);

    await page.workflowBuilder.save();

    expect(dom.window.document.getElementById('agentsV2WorkflowBuilderError')?.textContent)
      .toContain(inconsistencyMessage);
  });

  it('Workflow Builder edits dependency input and repository scope modes in the Node Editor', async () => {
    const fakeApi = api({ getWorkflow: vi.fn(() => Promise.resolve(workflow('wf', [
      portedNode('node-1', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      portedNode('node-2', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 260, 20)
    ], project().id, [connection('node-1', 'node-2')], 'node-1-input', 'node-2-output'))) });
    const { dom, page } = await openedBuilder(fakeApi);

    expect(dom.window.document.querySelector('[data-node-input-mode]')).toBeNull();
    clickNode(dom, 'node-1');
    expect(dom.window.document.querySelector('[data-node-editor-root-input]')?.textContent).toContain('Original task');
    dom.window.document.getElementById('agentsV2NodeEditorCancel')?.click();

    clickNode(dom, 'node-2');
    const dependentSelect = dom.window.document.querySelector<HTMLSelectElement>('[data-node-editor-input-mode]')!;
    expect([...dependentSelect.options].map((option) => option.textContent)).toEqual(['Dependencies only', 'Task + dependencies']);
    dependentSelect.value = 'TASK_AND_DEPENDENCIES';
    const scopeSelect = dom.window.document.querySelector<HTMLSelectElement>('[data-node-editor-scope-mode]')!;
    expect([...scopeSelect.options].map((option) => option.textContent)).toEqual(['Once', 'Per repository']);
    expect(scopeSelect.value).toBe('GLOBAL');
    scopeSelect.value = 'PER_SCOPE';
    dom.window.document.getElementById('agentsV2NodeEditorSave')?.click();
    await page.workflowBuilder.save();

    expect(fakeApi.updateWorkflow).toHaveBeenCalledWith('wf', {
      name: 'Full Testing',
      nodes: [
        {
          id: 'node-1',
          targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          inputMode: 'DEPENDENCIES_ONLY',
          scopeMode: 'GLOBAL',
          inputs: [{ id: 'node-1-input', name: 'Input', description: 'Default workflow input.', order: 0 }],
          outputs: [{ id: 'node-1-output', name: 'Output', description: 'Default workflow output.', order: 0 }],
          position: { x: 10, y: 20 }
        },
        {
          id: 'node-2',
          targetId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          inputMode: 'TASK_AND_DEPENDENCIES',
          scopeMode: 'PER_SCOPE',
          inputs: [{ id: 'node-2-input', name: 'Input', description: 'Default workflow input.', order: 0 }],
          outputs: [{ id: 'node-2-output', name: 'Output', description: 'Default workflow output.', order: 0 }],
          position: { x: 260, y: 20 }
        }
      ],
      connections: [{
        id: 'node-1-node-2-connection',
        sourceOutputPortId: 'node-1-output',
        targetInputPortId: 'node-2-input'
      }],
      taskInputPortId: 'node-1-input',
      taskOutputPortId: 'node-2-output'
    });
  });

  it('Workflow Builder rejects malformed node scope mode instead of defaulting to global', async () => {
    const { page } = await openedBuilder();

    expect(page.workflowBuilder.nodeScopeMode({ scopeMode: 'PER_SCOPE' })).toBe('PER_SCOPE');
    expect(() => page.workflowBuilder.nodeScopeMode({})).toThrow('Workflow node scope mode is invalid.');
    expect(() => page.workflowBuilder.nodeScopeMode({ scopeMode: 'repository' })).toThrow('Workflow node scope mode is invalid.');
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
    client.listProjectRepositories(project().id);
    client.importProjectRepository(project().id, { remoteUrl: 'git@gitlab.com:company/service-a.git' });
    client.cloneProjectRepository(project().id, '88888888-8888-4888-8888-888888888888');
    client.refreshProjectRepository(project().id, '88888888-8888-4888-8888-888888888888');
    client.pullProjectRepository(project().id, '88888888-8888-4888-8888-888888888888');
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
    const sshRequest = { name: 'Ancestor', host: '192.168.0.108', port: 22,
      username: 'ancestor', authType: 'PASSWORD', privateKeyPath: null, password: 'secret' };
    client.testSshConnection(project().id, sshRequest);

    const calls = [...http.get.mock.calls, ...http.post.mock.calls, ...http.put.mock.calls, ...http.delete.mock.calls].map(([path]) => path);
    expect(calls.every((path) => path.startsWith('/agents'))).toBe(true);
    expect(http.get).toHaveBeenCalledWith(`/agents/projects/${project().id}/repositories`);
    expect(http.post).toHaveBeenCalledWith(
      `/agents/projects/${project().id}/ssh-connections/test`, sshRequest);
    expect(http.post).toHaveBeenCalledWith(`/agents/projects/${project().id}/repositories`, {
      remoteUrl: 'git@gitlab.com:company/service-a.git'
    });
    expect(http.post).toHaveBeenCalledWith(`/agents/projects/${project().id}/repositories/88888888-8888-4888-8888-888888888888/clone`);
    expect(http.post).toHaveBeenCalledWith(`/agents/projects/${project().id}/repositories/88888888-8888-4888-8888-888888888888/refresh`);
    expect(http.post).toHaveBeenCalledWith(`/agents/projects/${project().id}/repositories/88888888-8888-4888-8888-888888888888/pull`);
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
  dom.window.document.querySelector<HTMLElement>(`[data-node-output-port="${source}-output"]`)!
    .dispatchEvent(pointer(dom, 'pointerdown', 200, 40));
  dom.window.document.querySelector<HTMLElement>(`[data-node-input-port="${target}-input"]`)!
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
