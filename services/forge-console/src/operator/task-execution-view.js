import { escapeHtml } from './dom-render-helpers.js';

const ACTIVE_RUN_STATUSES = new Set(['QUEUED', 'RUNNING']);
const NODE_WIDTH = 204;
const NODE_HEIGHT = 110;
const NODE_MID_Y = 58;
const MIN_CANVAS_WIDTH = 1600;
const MIN_CANVAS_HEIGHT = 1000;
const CANVAS_PADDING = 240;
const MIN_CANVAS_SCALE = 0.45;
const MAX_CANVAS_SCALE = 1.8;

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
    this.canvasPan = null;
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.state = this.emptyState();
  }

  bind() {
    this.handlePointerMove = (event) => this.onPointerMove(event);
    this.handlePointerUp = () => this.endCanvasPan();
    this.handlePointerCancel = () => this.endCanvasPan();
    this.handleCanvasPointerDown = (event) => this.onCanvasPointerDown(event);
    this.handleCanvasWheel = (event) => this.onCanvasWheel(event);
    this.document.addEventListener('pointermove', this.handlePointerMove);
    this.document.addEventListener('pointerup', this.handlePointerUp);
    this.document.addEventListener('pointercancel', this.handlePointerCancel);
    this.byId('agentsV2ExecutionCanvas')?.addEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2ExecutionCanvas')?.addEventListener('wheel', this.handleCanvasWheel, { passive: false });
    this.byId('agentsV2TaskExecutionBack')?.addEventListener('click', () => this.onBack());
  }

  dispose() {
    this.disposed = true;
    this.document.removeEventListener('pointermove', this.handlePointerMove);
    this.document.removeEventListener('pointerup', this.handlePointerUp);
    this.document.removeEventListener('pointercancel', this.handlePointerCancel);
    this.byId('agentsV2ExecutionCanvas')?.removeEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2ExecutionCanvas')?.removeEventListener('wheel', this.handleCanvasWheel);
    this.close();
  }

  close() {
    this.opened = false;
    this.taskLoadSequence += 1;
    this.runLoadSequence += 1;
    this.stopPolling();
    this.pollInFlight = null;
    this.canvasPan = null;
    this.state = this.emptyState();
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.applyViewportTransform();
    this.byId('agentsV2ExecutionCanvas')?.classList.remove('panning');
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
    const workflowName = this.state.workflowRun?.workflowName || this.selectedRunSummary()?.workflowName || 'Unknown workflow';
    const runStatus = this.state.workflowRun?.status || this.selectedRunSummary()?.status || 'UNKNOWN';
    const failedNodeRuns = (this.state.workflowRun?.nodeRuns || []).filter((nodeRun) => nodeRun.status === 'FAILED');
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
      ${runStatus === 'FAILED' && failedNodeRuns.length ? this.renderRunFailureSummary(failedNodeRuns) : ''}
    `;
    summary.querySelectorAll('[data-failed-node-run-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectNodeRun(element.dataset.failedNodeRunId));
    });
  }

  renderRunFailureSummary(failedNodeRuns) {
    return `
      <div class="task-execution-failure-summary">
        <strong>Failure</strong>
        ${failedNodeRuns.map((nodeRun) => `
          <button class="task-execution-failure-row" type="button" data-failed-node-run-id="${escapeHtml(nodeRun.id)}">
            <span>${escapeHtml(nodeRun.agentName || 'Unknown agent')}</span>
            <code>${escapeHtml(nodeRun.failure?.code || 'FAILURE')}</code>
            <small>${escapeHtml(nodeRun.failure?.message || 'Node execution failed.')}</small>
          </button>
        `).join('')}
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
      element.addEventListener('click', () => this.selectNodeRun(element.dataset.executionNodeId));
    });
    this.renderEdges(nodeRuns);
    this.syncCanvasBounds(nodeRuns);
    this.applyViewportTransform();
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
    const edges = this.executionEdges()
      .map((edge) => {
        const source = byId.get(edge.sourceNodeRunId);
        const target = byId.get(edge.targetNodeRunId);
        if (!source || !target) {
          return '';
        }
        const start = this.nodePoint(source, 'output');
        const end = this.nodePoint(target, 'input');
        return `
          <g class="workflow-edge execution-edge" data-edge-source="${escapeHtml(source.id)}" data-edge-target="${escapeHtml(target.id)}">
            <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2ExecutionArrow)" />
          </g>
        `;
      })
      .filter(Boolean);
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
        ${this.detailRow('Input mode', this.formatInputMode(nodeRun))}
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

  formatInputMode(nodeRun) {
    if (this.incomingExecutionEdges(nodeRun.id).length === 0) {
      return 'Original task';
    }
    if (nodeRun.inputMode === 'TASK_AND_DEPENDENCIES') {
      return 'Original task + previous outputs';
    }
    if (nodeRun.inputMode === 'DEPENDENCIES_ONLY') {
      return 'Previous outputs only';
    }
    return 'Unknown';
  }

  consumedConnectionResolutions(nodeRunId = null) {
    return (this.state.workflowRun?.connectionResolutions || [])
      .filter((resolution) => resolution.resolutionType === 'DELIVERED')
      .filter((resolution) => Boolean(resolution.consumedByNodeRunId))
      .filter((resolution) => !nodeRunId || resolution.consumedByNodeRunId === nodeRunId);
  }

  executionEdges() {
    const resolutionEdges = this.consumedConnectionResolutions()
      .map((resolution) => ({
        sourceNodeRunId: resolution.sourceNodeRunId,
        targetNodeRunId: resolution.consumedByNodeRunId
      }));
    if (resolutionEdges.length) {
      return resolutionEdges;
    }
    return this.state.workflowRun?.executionEdges || [];
  }

  incomingExecutionEdges(nodeRunId) {
    return this.executionEdges().filter((edge) => edge.targetNodeRunId === nodeRunId);
  }

  selectedNodeRun() {
    return (this.state.workflowRun?.nodeRuns || []).find((nodeRun) => nodeRun.id === this.state.selectedNodeRunId) || null;
  }

  selectNodeRun(nodeRunId) {
    this.state.selectedNodeRunId = nodeRunId;
    this.renderGraph();
    this.renderNodeDetails();
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
        return left.index - right.index;
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

  onCanvasPointerDown(event) {
    if (event.button !== 0) {
      return;
    }
    if (event.target?.closest?.('.execution-node, button, select, input, textarea')) {
      return;
    }
    event.preventDefault();
    this.canvasPan = {
      startX: event.clientX,
      startY: event.clientY,
      originalX: this.viewport.x,
      originalY: this.viewport.y
    };
    this.byId('agentsV2ExecutionCanvas')?.classList.add('panning');
  }

  onPointerMove(event) {
    if (!this.canvasPan) {
      return;
    }
    this.viewport = {
      ...this.viewport,
      x: this.canvasPan.originalX + (event.clientX - this.canvasPan.startX),
      y: this.canvasPan.originalY + (event.clientY - this.canvasPan.startY)
    };
    this.applyViewportTransform();
  }

  endCanvasPan() {
    this.canvasPan = null;
    this.byId('agentsV2ExecutionCanvas')?.classList.remove('panning');
  }

  onCanvasWheel(event) {
    if (!this.state.workflowRun) {
      return;
    }
    event.preventDefault();
    const canvas = this.byId('agentsV2ExecutionCanvas');
    if (!canvas) {
      return;
    }
    const canvasRect = canvas.getBoundingClientRect();
    const before = this.canvasPoint(event);
    const zoomFactor = event.deltaY < 0 ? 1.08 : 0.92;
    const scale = clamp(this.viewport.scale * zoomFactor, MIN_CANVAS_SCALE, MAX_CANVAS_SCALE);
    this.viewport = {
      scale,
      x: (event.clientX - canvasRect.left) - (before.x * scale),
      y: (event.clientY - canvasRect.top) - (before.y * scale)
    };
    this.applyViewportTransform();
  }

  canvasPoint(event) {
    const canvas = this.byId('agentsV2ExecutionCanvas');
    const rect = canvas?.getBoundingClientRect?.() || { left: 0, top: 0 };
    return {
      x: ((event.clientX - rect.left) - this.viewport.x) / this.viewport.scale,
      y: ((event.clientY - rect.top) - this.viewport.y) / this.viewport.scale
    };
  }

  syncCanvasBounds(nodeRuns) {
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    let width = MIN_CANVAS_WIDTH;
    let height = MIN_CANVAS_HEIGHT;
    for (const nodeRun of nodeRuns) {
      width = Math.max(width, Number(nodeRun.position?.x || 0) + NODE_WIDTH + CANVAS_PADDING);
      height = Math.max(height, Number(nodeRun.position?.y || 0) + NODE_HEIGHT + CANVAS_PADDING);
    }
    const widthValue = `${Math.ceil(width)}px`;
    const heightValue = `${Math.ceil(height)}px`;
    edgesSvg.style.width = widthValue;
    edgesSvg.style.height = heightValue;
    edgesSvg.setAttribute('width', String(Math.ceil(width)));
    edgesSvg.setAttribute('height', String(Math.ceil(height)));
    nodesLayer.style.width = widthValue;
    nodesLayer.style.height = heightValue;
  }

  applyViewportTransform() {
    const transform = `translate(${this.viewport.x}px, ${this.viewport.y}px) scale(${this.viewport.scale})`;
    for (const element of [this.byId('agentsV2ExecutionEdges'), this.byId('agentsV2ExecutionNodes')]) {
      if (!element) {
        continue;
      }
      element.style.transform = transform;
      element.style.transformOrigin = '0 0';
    }
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

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
