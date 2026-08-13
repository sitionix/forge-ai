import { escapeHtml } from './dom-render-helpers.js';

const ACTIVE_RUN_STATUSES = new Set(['QUEUED', 'RUNNING']);
const NODE_WIDTH = 204;
const NODE_MID_Y = 58;

export class TaskExecutionView {
  constructor(options) {
    this.document = options.document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api;
    this.onBack = options.onBack;
    this.pollIntervalMs = Number(options.runtimeConfig?.activeJobPollIntervalMs) || 2000;
    this.disposed = false;
    this.opened = false;
    this.taskLoadSequence = 0;
    this.runLoadSequence = 0;
    this.pollTimer = null;
    this.pollInFlight = null;
    this.state = this.emptyState();
  }

  bind() {
    this.byId('agentsV2TaskExecutionBack')?.addEventListener('click', () => this.onBack());
  }

  dispose() {
    this.disposed = true;
    this.close();
  }

  close() {
    this.opened = false;
    this.taskLoadSequence += 1;
    this.runLoadSequence += 1;
    this.stopPolling();
    this.pollInFlight = null;
    this.state = this.emptyState();
  }

  async open(taskId, project) {
    const taskSequence = this.taskLoadSequence + 1;
    this.taskLoadSequence = taskSequence;
    this.runLoadSequence += 1;
    this.stopPolling();
    this.pollInFlight = null;
    this.opened = true;
    this.disposed = false;
    this.state = {
      ...this.emptyState(),
      taskId,
      project,
      loadingTask: true
    };
    this.render();
    try {
      const task = await this.api.getProjectTask(taskId);
      if (!this.isCurrentTask(taskId, taskSequence)) {
        return;
      }
      this.state.task = task;
      this.state.loadingTask = false;
      this.state.taskError = '';
      this.render();
      const run = this.sortedRuns()[0];
      if (run?.id) {
        await this.selectRun(run.id);
      }
    } catch (error) {
      if (!this.isCurrentTask(taskId, taskSequence)) {
        return;
      }
      this.state.loadingTask = false;
      this.state.taskError = error.message || 'Task execution failed to load.';
      this.render();
    }
  }

  async selectRun(runId) {
    if (!this.state.task || !runId) {
      return;
    }
    const taskId = this.state.taskId;
    const taskSequence = this.taskLoadSequence;
    const runSequence = this.runLoadSequence + 1;
    this.runLoadSequence = runSequence;
    this.stopPolling();
    this.pollInFlight = null;
    this.state.selectedRunId = runId;
    this.state.selectedNodeRunId = null;
    this.state.workflowRun = null;
    this.state.loadingRun = true;
    this.state.executionError = '';
    this.state.refreshError = '';
    this.render();
    try {
      const workflowRun = await this.api.getWorkflowRun(runId);
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.applyWorkflowRun(workflowRun);
      this.state.loadingRun = false;
      this.render();
      this.syncPolling();
    } catch (error) {
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.state.loadingRun = false;
      this.state.executionError = error.message || 'Workflow run failed to load.';
      this.render();
    }
  }

  applyWorkflowRun(workflowRun) {
    this.state.workflowRun = workflowRun;
    this.state.refreshError = '';
    this.mergeRunSummary(workflowRun);
    const nodeRuns = workflowRun?.nodeRuns || [];
    if (!nodeRuns.some((nodeRun) => nodeRun.id === this.state.selectedNodeRunId)) {
      this.state.selectedNodeRunId = nodeRuns[0]?.id || null;
    }
  }

  mergeRunSummary(workflowRun) {
    const runs = this.state.task?.runs || [];
    this.state.task.runs = runs.map((run) => run.id === workflowRun.id ? { ...run, ...workflowRun } : run);
  }

  syncPolling() {
    if (this.shouldPoll()) {
      this.schedulePolling();
      return;
    }
    this.stopPolling();
  }

  schedulePolling() {
    if (this.disposed || !this.opened || this.pollTimer || this.pollInFlight) {
      return;
    }
    this.pollTimer = this.window.setTimeout(() => {
      this.pollTimer = null;
      this.pollSelectedRun();
    }, this.pollIntervalMs);
  }

  async pollSelectedRun() {
    if (this.disposed || !this.opened || !this.shouldPoll() || this.pollInFlight) {
      this.syncPolling();
      return;
    }
    const taskId = this.state.taskId;
    const taskSequence = this.taskLoadSequence;
    const runId = this.state.selectedRunId;
    const runSequence = this.runLoadSequence;
    const request = this.api.getWorkflowRun(runId);
    this.pollInFlight = request;
    try {
      const workflowRun = await request;
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.applyWorkflowRun(workflowRun);
      this.render();
    } catch (error) {
      if (!this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        return;
      }
      this.state.refreshError = error.message || 'Workflow run refresh failed.';
      this.render();
    } finally {
      if (this.pollInFlight === request) {
        this.pollInFlight = null;
      }
      if (!this.disposed && this.isCurrentRun(taskId, taskSequence, runId, runSequence)) {
        this.syncPolling();
      }
    }
  }

  shouldPoll() {
    return Boolean(
      !this.disposed
      && this.opened
      && this.state.selectedRunId
      && ACTIVE_RUN_STATUSES.has(this.state.workflowRun?.status)
    );
  }

  render() {
    this.renderHeader();
    this.renderTaskSummary();
    this.renderHistory();
    this.renderExecutionState();
    this.renderGraph();
    this.renderNodeDetails();
  }

  renderHeader() {
    const projectName = this.state.project?.name || 'Project';
    const taskTitle = this.state.task?.title || (this.state.loadingTask ? 'Loading task...' : 'Task execution');
    this.byId('agentsV2TaskExecutionCrumbs').textContent = `Projects / ${projectName} / Tasks`;
    this.byId('agentsV2TaskExecutionTitle').textContent = taskTitle;
  }

  renderTaskSummary() {
    this.showError('agentsV2TaskExecutionTaskError', this.state.taskError);
    const summary = this.byId('agentsV2TaskExecutionSummary');
    if (this.state.loadingTask) {
      summary.innerHTML = '<div class="muted-state">Loading task...</div>';
      return;
    }
    if (!this.state.task) {
      summary.innerHTML = '';
      return;
    }
    const workflowName = this.state.workflowRun?.workflowName || this.selectedRunSummary()?.workflowName || this.state.task.workflowName || 'Unknown workflow';
    const runStatus = this.state.workflowRun?.status || this.selectedRunSummary()?.status || 'UNKNOWN';
    summary.innerHTML = `
      <div class="task-execution-summary-grid">
        <div>
          <span>Task</span>
          <strong>${escapeHtml(this.state.task.input || '')}</strong>
        </div>
        <div>
          <span>Workflow</span>
          <strong>${escapeHtml(workflowName)}</strong>
        </div>
        <div>
          <span>Execution</span>
          <strong class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(runStatus))}" data-run-status="${escapeHtml(runStatus)}">${escapeHtml(runStatus)}</strong>
        </div>
      </div>
    `;
  }

  renderHistory() {
    const history = this.byId('agentsV2ExecutionHistory');
    if (this.state.loadingTask) {
      history.innerHTML = '<div class="muted-state compact">Loading executions...</div>';
      return;
    }
    if (!this.state.task) {
      history.innerHTML = '';
      return;
    }
    const runs = this.sortedRuns();
    if (!runs.length) {
      history.innerHTML = '<div class="muted-state compact">No executions yet.</div>';
      return;
    }
    history.innerHTML = runs.map((run) => `
      <button class="execution-history-row ${run.id === this.state.selectedRunId ? 'selected' : ''}" type="button" data-run-id="${escapeHtml(run.id)}">
        <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(run.status))}" data-history-run-status="${escapeHtml(run.status || 'UNKNOWN')}">${escapeHtml(run.status || 'UNKNOWN')}</span>
        <span>${escapeHtml(this.formatDate(run.createdAt || run.startedAt || run.finishedAt))}</span>
      </button>
    `).join('');
    history.querySelectorAll('[data-run-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectRun(element.dataset.runId));
    });
  }

  renderExecutionState() {
    this.showError('agentsV2TaskExecutionError', this.state.executionError);
    this.showError('agentsV2TaskExecutionRefreshError', this.state.refreshError);
    const state = this.byId('agentsV2ExecutionState');
    if (this.state.loadingRun) {
      state.innerHTML = '<div class="muted-state compact">Loading execution...</div>';
      return;
    }
    if (!this.state.taskError && this.state.task && !this.sortedRuns().length) {
      state.innerHTML = '<div class="muted-state compact">No executions yet.</div>';
      return;
    }
    state.innerHTML = '';
  }

  renderGraph() {
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const nodeRuns = this.state.workflowRun?.nodeRuns || [];
    if (!this.state.workflowRun || !nodeRuns.length) {
      nodesLayer.innerHTML = this.state.workflowRun && !nodeRuns.length
        ? '<div class="muted-state task-execution-graph-empty">No node runs yet.</div>'
        : '';
      edgesSvg.innerHTML = '';
      return;
    }
    nodesLayer.innerHTML = nodeRuns.map((nodeRun) => this.renderNode(nodeRun)).join('');
    nodesLayer.querySelectorAll('[data-execution-node-id]').forEach((element) => {
      element.addEventListener('click', () => {
        this.state.selectedNodeRunId = element.dataset.executionNodeId;
        this.renderGraph();
        this.renderNodeDetails();
      });
    });
    this.renderEdges(nodeRuns);
  }

  renderNode(nodeRun) {
    const status = nodeRun.status || 'PENDING';
    return `
      <article
        class="execution-node execution-node-${escapeHtml(statusTone(status))} ${nodeRun.id === this.state.selectedNodeRunId ? 'selected' : ''}"
        data-execution-node-id="${escapeHtml(nodeRun.id)}"
        data-node-status="${escapeHtml(status)}"
        style="left:${Number(nodeRun.position?.x || 0)}px; top:${Number(nodeRun.position?.y || 0)}px;"
      >
        <div class="execution-node-content">
          <strong>${escapeHtml(nodeRun.agentName || 'Unknown agent')}</strong>
          <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(status))}">${escapeHtml(status)}</span>
        </div>
      </article>
    `;
  }

  renderEdges(nodeRuns) {
    const byId = new Map(nodeRuns.map((nodeRun) => [nodeRun.id, nodeRun]));
    const edges = [];
    for (const target of nodeRuns) {
      for (const sourceId of target.dependsOnNodeRunIds || []) {
        const source = byId.get(sourceId);
        if (!source) {
          continue;
        }
        const start = this.nodePoint(source, 'output');
        const end = this.nodePoint(target, 'input');
        edges.push(`
          <g class="workflow-edge execution-edge" data-edge-source="${escapeHtml(source.id)}" data-edge-target="${escapeHtml(target.id)}">
            <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2ExecutionArrow)" />
          </g>
        `);
      }
    }
    this.byId('agentsV2ExecutionEdges').innerHTML = `
      <defs>
        <marker id="agentsV2ExecutionArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      ${edges.join('')}
    `;
  }

  renderNodeDetails() {
    const panel = this.byId('agentsV2NodeRunDetails');
    const nodeRun = this.selectedNodeRun();
    if (!nodeRun) {
      panel.innerHTML = '<div class="muted-state">Select a node to inspect its execution.</div>';
      return;
    }
    const failure = nodeRun.failure;
    panel.innerHTML = `
      <div class="node-run-details-grid">
        ${this.detailRow('Agent', nodeRun.agentName || 'Unknown agent')}
        ${this.detailRow('Status', nodeRun.status || 'PENDING')}
        ${this.detailRow('Instructions', nodeRun.agentInstructions || '')}
        ${this.detailRow('Started', this.formatDate(nodeRun.startedAt))}
        ${this.detailRow('Finished', this.formatDate(nodeRun.finishedAt))}
      </div>
      <section class="node-run-output">
        <h3>Output</h3>
        ${nodeRun.output == null ? '<div class="muted-state compact">No output yet.</div>' : `<pre>${escapeHtml(this.formatOutput(nodeRun.output))}</pre>`}
      </section>
      ${failure ? `
        <section class="node-run-failure">
          <h3>Failure</h3>
          <strong>${escapeHtml(failure.code || 'FAILURE')}</strong>
          <p>${escapeHtml(failure.message || '')}</p>
        </section>
      ` : ''}
    `;
  }

  detailRow(label, value) {
    return `
      <div class="node-run-detail-row">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(value || '-')}</strong>
      </div>
    `;
  }

  selectedNodeRun() {
    return (this.state.workflowRun?.nodeRuns || []).find((nodeRun) => nodeRun.id === this.state.selectedNodeRunId) || null;
  }

  selectedRunSummary() {
    return (this.state.task?.runs || []).find((run) => run.id === this.state.selectedRunId) || null;
  }

  sortedRuns() {
    return (this.state.task?.runs || [])
      .map((run, index) => ({ run, index }))
      .sort((left, right) => {
        const leftTime = this.runTime(left.run);
        const rightTime = this.runTime(right.run);
        if (leftTime !== rightTime) {
          return rightTime - leftTime;
        }
        return right.index - left.index;
      })
      .map((entry) => entry.run);
  }

  runTime(run) {
    const value = run.createdAt || run.startedAt || run.finishedAt || run.updatedAt;
    const time = value ? new Date(value).getTime() : Number.NaN;
    return Number.isNaN(time) ? 0 : time;
  }

  formatOutput(value) {
    if (typeof value === 'string') {
      try {
        return JSON.stringify(JSON.parse(value), null, 2);
      } catch (_) {
        return value;
      }
    }
    try {
      return JSON.stringify(value, null, 2);
    } catch (_) {
      return String(value);
    }
  }

  formatDate(value) {
    if (!value) {
      return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString();
  }

  nodePoint(nodeRun, kind) {
    return {
      x: Number(nodeRun.position?.x || 0) + (kind === 'output' ? NODE_WIDTH : 0),
      y: Number(nodeRun.position?.y || 0) + NODE_MID_Y
    };
  }

  pathD(start, end) {
    const mid = Math.max(40, Math.abs(end.x - start.x) / 2);
    return `M ${start.x} ${start.y} C ${start.x + mid} ${start.y}, ${end.x - mid} ${end.y}, ${end.x} ${end.y}`;
  }

  stopPolling() {
    if (this.pollTimer) {
      this.window.clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  isCurrentTask(taskId, taskSequence) {
    return !this.disposed
      && this.opened
      && this.state.taskId === taskId
      && this.taskLoadSequence === taskSequence;
  }

  isCurrentRun(taskId, taskSequence, runId, runSequence) {
    return this.isCurrentTask(taskId, taskSequence)
      && this.state.selectedRunId === runId
      && this.runLoadSequence === runSequence;
  }

  showError(id, message) {
    const element = this.byId(id);
    element.textContent = message || '';
    element.classList.toggle('hidden', !message);
  }

  byId(id) {
    return this.document.getElementById(id);
  }

  emptyState() {
    return {
      taskId: null,
      project: null,
      task: null,
      workflowRun: null,
      selectedRunId: null,
      selectedNodeRunId: null,
      loadingTask: false,
      loadingRun: false,
      taskError: '',
      executionError: '',
      refreshError: ''
    };
  }

  testApi() {
    return {
      open: (taskId, project) => this.open(taskId, project),
      selectRun: (runId) => this.selectRun(runId),
      close: () => this.close(),
      state: this.state
    };
  }
}

export function statusTone(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'queued') {
    return 'queued';
  }
  if (normalized === 'running') {
    return 'running';
  }
  if (normalized === 'succeeded') {
    return 'succeeded';
  }
  if (normalized === 'failed') {
    return 'failed';
  }
  if (normalized === 'blocked') {
    return 'blocked';
  }
  if (normalized === 'cancelled') {
    return 'cancelled';
  }
  if (normalized === 'pending') {
    return 'pending';
  }
  return 'unknown';
}
