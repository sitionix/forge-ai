import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import { TaskExecutionView } from '../src/operator/task-execution-view.js';
import { HttpError } from '../src/api/http-client';
import { createAgentProjectsApi } from '../src/operator/agent-projects-api.js';
// @ts-expect-error Production JavaScript is exercised through its DOM and HTTP contract.
import { createInfrastructureHttpClient } from '../src/operator/infrastructure-http-client.js';

const RUN = '11111111-1111-4111-8111-111111111111';
const MANUAL = '22222222-2222-4222-8222-222222222222';
const PORTS = ['33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444', '55555555-5555-4555-8555-555555555555'];
const cleanup: Array<() => void> = [];
afterEach(() => cleanup.splice(0).forEach((close) => close()));

function run(selected: string | null = null): any {
  return {
    id: RUN, workflowName: 'Manual workflow', status: 'RUNNING', createdAt: '2026-09-18T00:00:00Z',
    repositoryIds: [], connectionResolutions: [], executionEdges: [],
    runtimeGraph: {
      taskInputPortId: 'input', taskOutputPortId: 'review-output',
      nodes: [
        { sourceNodeId: 'manual', nodeType: 'MANUAL', agentName: null, scopeMode: 'GLOBAL', position: { x: 20, y: 20 } },
        { sourceNodeId: 'reviewer', nodeType: 'AGENT', agentName: 'Reviewer', scopeMode: 'GLOBAL', position: { x: 400, y: 20 } }
      ],
      ports: [
        { sourceNodeId: 'manual', sourcePortId: 'input', direction: 'INPUT', name: 'Input', order: 0 },
        ...PORTS.map((id, order) => ({ sourceNodeId: 'manual', sourcePortId: id, direction: 'OUTPUT', name: ['Retry', 'Skip', 'Stop'][order], order })),
        { sourceNodeId: 'reviewer', sourcePortId: 'review-input', direction: 'INPUT', name: 'Input', order: 0 },
        { sourceNodeId: 'reviewer', sourcePortId: 'review-output', direction: 'OUTPUT', name: 'Done', order: 0 }
      ],
      connections: [{ sourceConnectionId: 'edge', sourceOutputPortId: PORTS[1], targetInputPortId: 'review-input' }]
    },
    nodeRuns: [
      { id: MANUAL, sourceNodeId: 'manual', nodeType: 'MANUAL', status: selected ? 'SUCCEEDED' : 'WAITING_FOR_MANUAL',
        selectedOutputPortId: selected, output: selected ? { selectedOutputPortId: selected, selectedOutputName: 'stale label' } : null,
        repositoryId: null, createdAt: '2026-09-18T00:00:00Z' },
      ...(selected ? [{ id: 'review-invocation', sourceNodeId: 'reviewer', nodeType: 'AGENT', agentName: 'Reviewer', agentInstructions: 'Review the change.',
        status: 'PENDING', repositoryId: null, createdAt: '2026-09-18T00:01:00Z' }] : [])
    ]
  };
}

function deferred() {
  let resolve!: (value: any) => void;
  let reject!: (error: any) => void;
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }

async function opened(initial = run()) {
  const dom = new JSDOM(readFileSync('src/operator/agent-projects.html', 'utf8'), { url: 'http://localhost/', pretendToBeVisual: true });
  const api = {
    getProjectTask: vi.fn(async () => ({ id: 'task', input: 'Do work', runs: [initial] })),
    getWorkflowRun: vi.fn(async () => initial),
    getAgentExecutionContexts: vi.fn(async () => []),
    getAgentExecutionEvents: vi.fn(),
    cancelWorkflowRun: vi.fn(),
    resetAgentExecutionContext: vi.fn(),
    retryRecoveredNodeRun: vi.fn(),
    selectManualNodeOutput: vi.fn(async () => run(PORTS[1]!))
  };
  const view = new TaskExecutionView({ document: dom.window.document, window: dom.window, api,
    onBack: vi.fn(), runtimeConfig: { activeJobPollIntervalMs: 60_000 } });
  view.bind();
  cleanup.push(() => { view.dispose(); dom.window.close(); });
  await view.open('task', { name: 'Project' });
  view.selectNodeRun(MANUAL);
  const panel = dom.window.document.getElementById('agentsV2NodeRunDetails')!;
  const buttons = () => [...panel.querySelectorAll<HTMLButtonElement>('[data-manual-output-port]')];
  return { dom, api, view, panel, buttons };
}

describe('Manual Task Execution', () => {
  it('selects a runtime output through the real API and HTTP client and renders the returned downstream activation', async () => {
    const dom = new JSDOM(readFileSync('src/operator/agent-projects.html', 'utf8'), {
      url: 'http://localhost/forge/operator/agent-projects.html', pretendToBeVisual: true
    });
    const base = '/forge/api/v1/infrastructure/agents';
    const selectionUrl = `${base}/workflow-runs/${RUN}/node-runs/${MANUAL}/manual-selection`;
    const selection = deferred();
    const initial = run();
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      if (init.method === 'GET' && url === `${base}/tasks/task`) {
        return Response.json({ id: 'task', input: 'Do work', runs: [initial] });
      }
      if (init.method === 'GET' && url === `${base}/workflow-runs/${RUN}`) return Response.json(initial);
      if (init.method === 'GET' && url === `${base}/workflow-runs/${RUN}/agent-execution-contexts`) return Response.json([]);
      if (init.method === 'POST' && url === selectionUrl) return selection.promise;
      throw new Error(`Unexpected HTTP request: ${init.method} ${url}`);
    });
    const http = createInfrastructureHttpClient({ window: dom.window, fetcher });
    const api = createAgentProjectsApi(http);
    const view = new TaskExecutionView({ document: dom.window.document, window: dom.window, api,
      onBack: vi.fn(), runtimeConfig: { activeJobPollIntervalMs: 60_000 } });
    view.bind();
    cleanup.push(() => { view.dispose(); dom.window.close(); });
    await view.open('task', { name: 'Project' });
    view.selectNodeRun(MANUAL);
    const panel = dom.window.document.getElementById('agentsV2NodeRunDetails')!;
    expect(panel.textContent).toContain('WAITING_FOR_MANUAL');
    expect([...panel.querySelectorAll<HTMLButtonElement>('[data-manual-output-port]')]
      .map((button) => button.dataset.manualOutputPort)).toEqual(PORTS);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `${base}/tasks/task`, `${base}/workflow-runs/${RUN}`, `${base}/workflow-runs/${RUN}/agent-execution-contexts`
    ]);

    panel.querySelector<HTMLButtonElement>(`[data-manual-output-port="${PORTS[1]}"]`)!.click();
    const posts = fetcher.mock.calls.filter(([, init]) => init.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]).toEqual([selectionUrl, expect.objectContaining({
      method: 'POST', cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ outputPortId: PORTS[1] })
    })]);
    expect([...panel.querySelectorAll<HTMLButtonElement>('[data-manual-output-port]')]
      .every((button) => button.disabled)).toBe(true);

    selection.resolve(Response.json(run(PORTS[1]!)));
    await vi.waitFor(() => expect(panel.textContent).toContain('Selected: Skip'));
    expect(panel.textContent).toContain('SUCCEEDED');
    expect(panel.textContent).not.toContain('stale label');
    expect(panel.querySelector('[data-manual-output-port]')).toBeNull();
    expect(view.state.workflowRun.nodeRuns[0].selectedOutputPortId).toBe(PORTS[1]);
    expect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')?.textContent).toContain('PENDING');
    expect(view.shouldPoll()).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(4);
  });

  it('renders one button per snapshotted output with Manual presentation and no agent sections', async () => {
    const { dom, panel, buttons, api } = await opened();
    expect(buttons().map((b) => b.textContent?.trim())).toEqual(['Retry', 'Skip', 'Stop']);
    expect(buttons().map((b) => b.dataset.manualOutputPort)).toEqual(PORTS);
    expect(panel.textContent).toContain('Manual action');
    expect(panel.textContent).toContain('WAITING_FOR_MANUAL');
    expect(panel.querySelector('.node-run-prompt-details, .node-run-activity, .node-run-context, [data-reset-agent-context], [data-retry-recovered-node-run]')).toBeNull();
    expect(panel.textContent).not.toMatch(/Unknown agent|Agent Activity|Provider|Model|Context/);
    const card = dom.window.document.querySelector('[data-execution-source-node-id="manual"]')!;
    expect(card.textContent).toContain('Manual');
    expect(card.textContent).toContain('WAITING_FOR_MANUAL');
    expect(api.getAgentExecutionEvents).not.toHaveBeenCalled();
  });

  it('sends exact IDs, disables all buttons, updates selected result and downstream, and resumes polling', async () => {
    const { api, view, dom, panel, buttons } = await opened();
    const response = deferred();
    api.selectManualNodeOutput.mockReturnValueOnce(response.promise);
    buttons()[1]!.click();
    expect(api.selectManualNodeOutput).toHaveBeenCalledExactlyOnceWith(RUN, MANUAL, PORTS[1]);
    expect(buttons().every((b) => b.disabled)).toBe(true);
    buttons()[0]!.click();
    expect(api.selectManualNodeOutput).toHaveBeenCalledTimes(1);
    response.resolve(run(PORTS[1]!));
    await flush();
    expect(buttons()).toHaveLength(0);
    expect(panel.textContent).toContain('Selected: Skip');
    expect(panel.textContent).toContain('SUCCEEDED');
    expect(panel.textContent).not.toContain('stale label');
    expect(dom.window.document.querySelector('[data-execution-source-node-id="reviewer"]')?.textContent).toContain('PENDING');
    expect(view.shouldPoll()).toBe(true);
    expect(view.pollTimer).not.toBeNull();
    view.selectNodeRun('review-invocation');
    expect(panel.querySelector('.node-run-prompt-details')).not.toBeNull();
    expect(panel.textContent).toContain('Review the change.');
  });

  it('shows typed HTTP errors and restores all buttons for retry', async () => {
    const { api, panel, buttons } = await opened();
    api.selectManualNodeOutput.mockRejectedValueOnce(new HttpError(409, { code: 'MANUAL_SELECTION_CONFLICT' }, 'Another output was selected.'));
    buttons()[0]!.click();
    await flush();
    expect(panel.querySelector('[role="alert"]')?.textContent).toContain('MANUAL_SELECTION_CONFLICT');
    expect(panel.textContent).toContain('Another output was selected.');
    expect(buttons()).toHaveLength(3);
    expect(buttons().every((b) => !b.disabled)).toBe(true);
  });

  it('renders an already selected invocation using snapshot name', async () => {
    const { panel, buttons } = await opened(run(PORTS[0]!));
    expect(panel.textContent).toContain('Selected: Retry');
    expect(buttons()).toHaveLength(0);
  });

  it('discards selection response after switching workflow runs', async () => {
    const { view, api, buttons } = await opened();
    const response = deferred();
    api.selectManualNodeOutput.mockReturnValueOnce(response.promise);
    buttons()[1]!.click();
    api.getWorkflowRun.mockResolvedValueOnce({ ...run(), id: 'other' });
    await view.selectRun('other');
    response.resolve(run(PORTS[1]!));
    await flush();
    expect(view.state.workflowRun.id).toBe('other');
    expect(view.state.workflowRun.nodeRuns[0].status).toBe('WAITING_FOR_MANUAL');
    view.selectNodeRun(MANUAL);
    expect(buttons().every((b) => !b.disabled)).toBe(true);
  });

  it('does not regress a committed selection when an earlier polling response arrives late', async () => {
    const { view, api, buttons, panel } = await opened();
    const stale = deferred();
    api.getWorkflowRun.mockReturnValueOnce(stale.promise);
    const polling = view.pollSelectedRun();
    buttons()[1]!.click();
    await flush();
    stale.resolve(run());
    await polling;
    expect(panel.textContent).toContain('Selected: Skip');
    expect(buttons()).toHaveLength(0);
  });

  it('keeps another mutation from invalidating an in-flight manual selection', async () => {
    const { dom, api, view, buttons, panel } = await opened();
    const response = deferred();
    api.selectManualNodeOutput.mockReturnValueOnce(response.promise);
    buttons()[1]!.click();
    expect(dom.window.document.querySelector<HTMLButtonElement>('[data-stop-run]')?.disabled).toBe(true);
    await view.stopSelectedRun();
    await view.resetAgentExecutionContext();
    await view.retryRecoveredNodeRun();
    expect(api.cancelWorkflowRun).not.toHaveBeenCalled();
    expect(api.resetAgentExecutionContext).not.toHaveBeenCalled();
    expect(api.retryRecoveredNodeRun).not.toHaveBeenCalled();
    response.resolve(run(PORTS[1]!));
    await flush();
    expect(panel.textContent).toContain('Selected: Skip');
  });

  it('keeps a selected historical invocation readonly while the next manual invocation waits', async () => {
    const initial = run(PORTS[0]!);
    const nextId = '66666666-6666-4666-8666-666666666666';
    initial.nodeRuns.push({ ...initial.nodeRuns[0], id: nextId, status: 'WAITING_FOR_MANUAL', selectedOutputPortId: null,
      output: null, createdAt: '2026-09-18T00:02:00Z' });
    const { api, view, panel, buttons } = await opened(initial);
    expect(panel.textContent).toContain('Selected: Retry');
    expect(buttons()).toHaveLength(0);
    const response = deferred();
    api.selectManualNodeOutput.mockReturnValueOnce(response.promise);
    view.selectNodeRun(nextId);
    buttons()[2]!.click();
    expect(api.selectManualNodeOutput).toHaveBeenCalledExactlyOnceWith(RUN, nextId, PORTS[2]);
    view.selectNodeRun(MANUAL);
    const updated = structuredClone(initial);
    updated.nodeRuns.at(-1).status = 'SUCCEEDED';
    updated.nodeRuns.at(-1).selectedOutputPortId = PORTS[2];
    response.resolve(updated);
    await flush();
    expect(view.state.selectedNodeRunId).toBe(MANUAL);
    expect(panel.textContent).toContain('Selected: Retry');
    expect(buttons()).toHaveLength(0);
    view.selectNodeRun(nextId);
    expect(panel.textContent).toContain('Selected: Stop');
  });

  it('uses arbitrary snapshot labels safely and never offers another node or input port', async () => {
    const initial = run();
    initial.runtimeGraph.ports.find((p: any) => p.sourcePortId === PORTS[0]).name = '<img src=x onerror=alert(1)> & Continue';
    const { panel, buttons } = await opened(initial);
    expect(buttons()).toHaveLength(3);
    expect(buttons()[0]!.textContent).toContain('<img src=x onerror=alert(1)> & Continue');
    expect(panel.querySelector('img')).toBeNull();
  });

  it('does not offer actions for a cancelled workflow or pending Manual invocation', async () => {
    const initial = run();
    initial.status = 'CANCELLED';
    const first = await opened(initial);
    expect(first.buttons()).toHaveLength(0);
    const pending = run();
    pending.nodeRuns[0].status = 'PENDING';
    const second = await opened(pending);
    expect(second.buttons()).toHaveLength(0);
  });
});
