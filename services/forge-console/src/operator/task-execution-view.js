import { escapeHtml } from './dom-render-helpers.js';

const ACTIVE_RUN_STATUSES = new Set(['QUEUED', 'RUNNING']);
const NODE_WIDTH = 252;
const NODE_MIN_HEIGHT = 132;
const NODE_HEADER_HEIGHT = 28;
const NODE_METRIC_ROW_HEIGHT = 18;
const NODE_SECTION_TOP_GAP = 10;
const NODE_SECTION_LABEL_HEIGHT = 14;
const NODE_PORT_ROW_HEIGHT = 22;
const NODE_HISTORY_HEIGHT = 28;
const NODE_VERTICAL_PADDING = 24;
const EDGE_TOKEN_DURATION_MS = 950;
const LEGACY_NODE_WIDTH = 204;
const LEGACY_NODE_HEIGHT = 110;
const NODE_MID_Y = 58;
const MIN_CANVAS_WIDTH = 1600;
const MIN_CANVAS_HEIGHT = 1000;
const CANVAS_PADDING = 240;
const MIN_CANVAS_SCALE = 0.45;
const MAX_CANVAS_SCALE = 1.8;
const HISTORY_MARKER_LIMIT = 8;

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
    this.fitAppliedRunId = null;
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
    this.fitAppliedRunId = null;
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
    this.fitAppliedRunId = null;
    this.state.selectedRunId = runId;
    this.state.selectedNodeRunId = null;
    this.state.selectedSourceNodeId = null;
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
    const previous = this.state.workflowRun;
    const sameRun = previous?.id && previous.id === workflowRun?.id;
    const animationDelta = sameRun ? this.detectPollAnimations(previous, workflowRun) : this.emptyAnimations();
    this.state.workflowRun = workflowRun;
    this.state.refreshError = '';
    this.mergeRunSummary(workflowRun);
    if (this.hasRuntimeGraph(workflowRun)) {
      this.syncModernSelection(workflowRun);
      return animationDelta;
    }
    const nodeRuns = workflowRun?.nodeRuns || [];
    if (!nodeRuns.some((nodeRun) => nodeRun.id === this.state.selectedNodeRunId)) {
      this.state.selectedNodeRunId = nodeRuns[0]?.id || null;
    }
    this.state.selectedSourceNodeId = null;
    return animationDelta;
  }

  syncModernSelection(workflowRun) {
    const graphNodes = workflowRun.runtimeGraph.nodes || [];
    const selectedNodeStillExists = graphNodes.some((node) => node.sourceNodeId === this.state.selectedSourceNodeId);
    if (!selectedNodeStillExists) {
      this.state.selectedSourceNodeId = graphNodes[0]?.sourceNodeId || null;
    }
    const nodeRuns = workflowRun.nodeRuns || [];
    if (!nodeRuns.some((nodeRun) => nodeRun.id === this.state.selectedNodeRunId)) {
      this.state.selectedNodeRunId = this.latestNodeRunForSource(this.state.selectedSourceNodeId, nodeRuns)?.id || null;
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
      const animationDelta = this.applyWorkflowRun(workflowRun);
      this.render(animationDelta);
      if (this.state.followActive && this.hasRuntimeGraph(workflowRun)) {
        this.followNewActiveNode(animationDelta);
      }
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

  render(animationDelta = null) {
    this.renderHeader();
    this.renderTaskSummary();
    this.renderHistory();
    this.renderExecutionState();
    this.renderGraph(animationDelta || this.emptyAnimations());
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
      element.addEventListener('click', () => this.selectConcreteNodeRun(element.dataset.failedNodeRunId));
    });
  }

  renderRunFailureSummary(failedNodeRuns) {
    return `
      <div class="task-execution-failure-summary">
        <strong>Failure</strong>
        ${failedNodeRuns.map((nodeRun) => `
          <button class="task-execution-failure-row" type="button" data-failed-node-run-id="${escapeHtml(nodeRun.id)}">
            <span>${escapeHtml(nodeRun.agentName || this.agentNameForSource(nodeRun.sourceNodeId) || 'Unknown agent')}</span>
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
    if (!this.hasRuntimeGraph(this.state.workflowRun)) {
      state.innerHTML = this.state.workflowRun ? '<div class="execution-board-legacy-note">Legacy execution trace</div>' : '';
      return;
    }
    const projection = this.modernProjection();
    const summary = projection.summary;
    const activeRows = projection.activeNodeRuns.slice(0, 4).map((nodeRun) => {
      const number = projection.invocationNumberById.get(nodeRun.id) || 1;
      return `<span>${escapeHtml(this.agentNameForSource(nodeRun.sourceNodeId) || nodeRun.agentName || 'Unknown agent')} · #${number} · ${escapeHtml(nodeRun.status)}</span>`;
    }).join('');
    state.innerHTML = `
      <div class="execution-board-strip">
        <div class="execution-board-status">
          <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(this.state.workflowRun.status))}">${escapeHtml(this.state.workflowRun.status || 'UNKNOWN')}</span>
        </div>
        ${this.summaryMetric('Running', summary.running)}
        ${this.summaryMetric('Pending', summary.pending)}
        ${this.summaryMetric('Routing', summary.routing)}
        ${this.summaryMetric('NodeRuns', summary.totalNodeRuns)}
        ${this.summaryMetric('Reached', `${summary.reached} / ${summary.totalNodes}`)}
        ${this.summaryMetric('Elapsed', this.elapsedLabel(this.state.workflowRun))}
        <div class="execution-board-last-activity">
          <span>Last activity</span>
          <strong>${escapeHtml(summary.lastActivity?.label || '-')}</strong>
        </div>
        <div class="execution-board-active-now">
          <span>Active now</span>
          <strong>${activeRows || '-'}</strong>
        </div>
        <div class="execution-board-controls">
          <button class="button small secondary" type="button" data-execution-control="fit">Fit</button>
          <button class="button small secondary" type="button" data-execution-control="center-active">Center active</button>
          <button class="button small ${this.state.followActive ? '' : 'secondary'}" type="button" data-execution-control="follow-active">Follow active</button>
        </div>
      </div>
    `;
    state.querySelector('[data-execution-control="fit"]')?.addEventListener('click', () => this.fitTopology());
    state.querySelector('[data-execution-control="center-active"]')?.addEventListener('click', () => this.centerActive());
    state.querySelector('[data-execution-control="follow-active"]')?.addEventListener('click', () => {
      this.state.followActive = !this.state.followActive;
      this.renderExecutionState();
    });
  }

  summaryMetric(label, value) {
    return `
      <div class="execution-board-metric">
        <span>${escapeHtml(label)}</span>
        <strong>${escapeHtml(String(value))}</strong>
      </div>
    `;
  }

  renderGraph(animationDelta = null) {
    if (this.hasRuntimeGraph(this.state.workflowRun)) {
      this.renderModernGraph(animationDelta || this.emptyAnimations());
      return;
    }
    this.renderLegacyGraph();
  }

  renderModernGraph(animationDelta = null) {
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const animations = animationDelta || this.emptyAnimations();
    const projection = this.modernProjection();
    if (!this.state.workflowRun) {
      nodesLayer.innerHTML = '';
      edgesSvg.innerHTML = '';
      return;
    }
    nodesLayer.innerHTML = projection.graph.nodes.map((node) => this.renderModernNode(node, projection, animations)).join('');
    nodesLayer.querySelectorAll('[data-execution-source-node-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectSourceNode(element.dataset.executionSourceNodeId));
    });
    nodesLayer.querySelectorAll('[data-execution-run-chip-id]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.selectConcreteNodeRun(element.dataset.executionRunChipId);
      });
    });
    this.syncCanvasBounds(projection.graph.nodes, false, projection);
    this.renderModernEdges(projection, animations);
    this.applyViewportTransform();
    if (this.fitAppliedRunId !== this.state.workflowRun.id) {
      this.fitTopology();
      this.fitAppliedRunId = this.state.workflowRun.id;
    }
  }

  renderModernNode(node, projection, animations = null) {
    const animationDelta = animations || this.emptyAnimations();
    const nodeRuns = projection.nodeRunsBySource.get(node.sourceNodeId) || [];
    const latest = nodeRuns[nodeRuns.length - 1] || null;
    const latestNumber = latest ? projection.invocationNumberById.get(latest.id) : null;
    const running = nodeRuns.filter((nodeRun) => nodeRun.status === 'RUNNING').length;
    const pending = nodeRuns.filter((nodeRun) => nodeRun.status === 'PENDING').length;
    const failed = nodeRuns.filter((nodeRun) => nodeRun.status === 'FAILED').length;
    const routing = nodeRuns.filter((nodeRun) => nodeRun.status === 'SUCCEEDED' && !nodeRun.routingCompletedAt).length;
    const inputPorts = projection.inputPortsByNode.get(node.sourceNodeId) || [];
    const outputPorts = projection.outputPortsByNode.get(node.sourceNodeId) || [];
    const inputCounts = projection.inputCountsByNode.get(node.sourceNodeId) || new Map();
    const outputCounts = projection.outputCountsByNode.get(node.sourceNodeId) || new Map();
    const selected = node.sourceNodeId === this.state.selectedSourceNodeId;
    const animationClasses = [
      animationDelta.nodeRunIds.has(latest?.id) ? 'execution-node-new-fact' : '',
      running ? 'execution-node-has-running' : '',
      failed ? 'execution-node-has-failed' : '',
      !nodeRuns.length ? 'execution-node-unreached' : '',
      selected ? 'selected' : ''
    ].filter(Boolean).join(' ');
    const markers = this.renderInvocationMarkers(nodeRuns, projection);
    return `
      <article
        class="execution-node execution-board-node ${animationClasses}"
        data-execution-source-node-id="${escapeHtml(node.sourceNodeId)}"
        data-execution-node-id="${escapeHtml(node.sourceNodeId)}"
        style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px; width:${NODE_WIDTH}px;"
      >
        <div class="execution-board-node-head">
          <strong>${escapeHtml(node.agentName || 'Unknown agent')}</strong>
        </div>
        <div class="execution-board-node-metrics">
          <span>Executions</span><strong>${nodeRuns.length}</strong>
          ${running ? `<span>Running</span><strong>${running}</strong>` : ''}
          ${pending ? `<span>Pending</span><strong>${pending}</strong>` : ''}
          ${failed ? `<span>Failed</span><strong>${failed}</strong>` : ''}
          ${latest ? `<span>Last</span><strong>#${latestNumber} · ${escapeHtml(latest.status)}</strong>` : '<span></span><strong>No executions yet</strong>'}
          ${routing ? `<span>Routing pending</span><strong>${routing}</strong>` : ''}
        </div>
        ${this.renderPortUsage('Inputs', inputPorts, inputCounts, animationDelta.inputPortIds, 'input')}
        ${this.renderPortUsage('Outputs', outputPorts, outputCounts, animationDelta.outputPortIds, 'output')}
        ${markers ? `<div class="execution-board-history">${markers}</div>` : ''}
      </article>
    `;
  }

  renderPortUsage(label, ports, counts, highlightedPortIds, side) {
    if (!ports.length) {
      return '';
    }
    return `
      <div class="execution-board-port-usage execution-board-port-usage-${escapeHtml(side)}">
        <span>${escapeHtml(label)}</span>
        ${ports.map((port) => `
          <div
            class="execution-board-port-row execution-board-port-row-${escapeHtml(side)} ${highlightedPortIds.has(port.sourcePortId) ? 'execution-port-new-fact' : ''}"
            data-runtime-port-id="${escapeHtml(port.sourcePortId)}"
          >
            <i
              class="execution-port-anchor"
              aria-hidden="true"
              data-runtime-port-anchor-id="${escapeHtml(port.sourcePortId)}"
            ></i>
            <small>${escapeHtml(port.name || 'Port')}</small>
            <strong>×${Number(counts.get(port.sourcePortId) || 0)}</strong>
          </div>
        `).join('')}
      </div>
    `;
  }

  renderInvocationMarkers(nodeRuns, projection) {
    if (!nodeRuns.length) {
      return '';
    }
    const hidden = Math.max(0, nodeRuns.length - HISTORY_MARKER_LIMIT);
    const visible = nodeRuns.slice(-HISTORY_MARKER_LIMIT);
    return `
      ${hidden ? `<span class="execution-history-overflow">+${hidden}</span>` : ''}
      ${visible.map((nodeRun) => {
        const number = projection.invocationNumberById.get(nodeRun.id) || 1;
        const title = `Execution #${number}\n${nodeRun.status}\n${this.formatDate(nodeRun.startedAt || nodeRun.createdAt)}`;
        return `<button class="execution-history-marker execution-history-marker-${escapeHtml(statusTone(nodeRun.status))}" type="button" title="${escapeHtml(title)}" data-execution-run-chip-id="${escapeHtml(nodeRun.id)}">${escapeHtml(statusSymbol(nodeRun.status))}</button>`;
      }).join('')}
    `;
  }

  renderModernEdges(projection, animations = null) {
    const animationDelta = animations || this.emptyAnimations();
    const edges = projection.graph.connections.map((connection) => {
      const sourcePort = projection.portById.get(connection.sourceOutputPortId);
      const targetPort = projection.portById.get(connection.targetInputPortId);
      const sourceNode = sourcePort ? projection.nodeBySource.get(sourcePort.sourceNodeId) : null;
      const targetNode = targetPort ? projection.nodeBySource.get(targetPort.sourceNodeId) : null;
      if (!sourceNode || !targetNode) {
        return '';
      }
      const delivered = projection.deliveredByConnection.get(connection.sourceConnectionId) || 0;
      const closed = projection.closedByConnection.get(connection.sourceConnectionId) || 0;
      const start = this.modernPortPoint(sourcePort, projection);
      const end = this.modernPortPoint(targetPort, projection);
      const labelPoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 - 10 };
      const path = this.pathD(start, end);
      const token = animationDelta.connectionResolutionIds.size
        ? this.renderEdgeToken(connection, projection, animationDelta, path)
        : '';
      const title = `${sourceNode.agentName}.${sourcePort.name} → ${targetNode.agentName}.${targetPort.name} · DELIVERED ${delivered} · CLOSED ${closed}`;
      return `
        <g
          class="workflow-edge execution-edge execution-topology-edge ${delivered ? 'execution-edge-delivered' : ''}"
          data-runtime-connection-id="${escapeHtml(connection.sourceConnectionId)}"
          data-runtime-source-port-id="${escapeHtml(sourcePort.sourcePortId)}"
          data-runtime-target-port-id="${escapeHtml(targetPort.sourcePortId)}"
        >
          <title>${escapeHtml(title)}</title>
          <path class="edge-visible" d="${path}" marker-end="url(#agentsV2ExecutionArrow)" />
          ${delivered ? `<text class="execution-edge-count" x="${labelPoint.x}" y="${labelPoint.y}">×${delivered}</text>` : ''}
          ${token}
        </g>
      `;
    }).filter(Boolean);
    this.byId('agentsV2ExecutionEdges').innerHTML = `
      <defs>
        <marker id="agentsV2ExecutionArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      ${edges.join('')}
    `;
    this.scheduleEdgeTokenCleanup();
  }

  renderEdgeToken(connection, projection, animationDelta, path) {
    if (this.prefersReducedMotion()) {
      return '';
    }
    const hasNewResolution = (this.state.workflowRun?.connectionResolutions || [])
      .some((resolution) => (
        resolution.sourceConnectionId === connection.sourceConnectionId
        && resolution.resolutionType === 'DELIVERED'
        && animationDelta.connectionResolutionIds.has(resolution.id)
      ));
    if (!hasNewResolution) {
      return '';
    }
    return `
      <circle class="execution-edge-token" r="5" data-edge-animation-id="${escapeHtml(connection.sourceConnectionId)}">
        <animateMotion dur="${EDGE_TOKEN_DURATION_MS}ms" fill="remove" path="${escapeHtml(path)}"></animateMotion>
      </circle>
    `;
  }

  scheduleEdgeTokenCleanup() {
    for (const token of this.document.querySelectorAll('[data-edge-animation-id]')) {
      this.window.setTimeout(() => token.remove(), EDGE_TOKEN_DURATION_MS + 50);
    }
  }

  renderLegacyGraph() {
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
    nodesLayer.innerHTML = nodeRuns.map((nodeRun) => this.renderLegacyNode(nodeRun)).join('');
    nodesLayer.querySelectorAll('[data-execution-node-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectNodeRun(element.dataset.executionNodeId));
    });
    this.renderLegacyEdges(nodeRuns);
    this.syncCanvasBounds(nodeRuns, true);
    this.applyViewportTransform();
  }

  renderLegacyNode(nodeRun) {
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

  renderLegacyEdges(nodeRuns) {
    const byId = new Map(nodeRuns.map((nodeRun) => [nodeRun.id, nodeRun]));
    const edges = this.executionEdges()
      .map((edge) => {
        const source = byId.get(edge.sourceNodeRunId);
        const target = byId.get(edge.targetNodeRunId);
        if (!source || !target) {
          return '';
        }
        const start = this.legacyNodePoint(source, 'output');
        const end = this.legacyNodePoint(target, 'input');
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
    if (this.hasRuntimeGraph(this.state.workflowRun)) {
      this.renderModernDetails();
      return;
    }
    this.renderLegacyNodeDetails();
  }

  renderModernDetails() {
    const panel = this.byId('agentsV2NodeRunDetails');
    const projection = this.modernProjection();
    const node = projection.nodeBySource.get(this.state.selectedSourceNodeId);
    if (!node) {
      panel.innerHTML = '<div class="muted-state">Select a workflow node to inspect execution.</div>';
      return;
    }
    const selectedTab = this.state.detailsTab || 'details';
    panel.innerHTML = `
      <div class="execution-details-tabs">
        <button class="${selectedTab === 'details' ? 'selected' : ''}" type="button" data-details-tab="details">Details</button>
        <button class="${selectedTab === 'activity' ? 'selected' : ''}" type="button" data-details-tab="activity">Activity</button>
      </div>
      ${selectedTab === 'activity' ? this.renderActivityPanel(projection) : this.renderNodeDetailsPanel(node, projection)}
    `;
    panel.querySelectorAll('[data-details-tab]').forEach((element) => {
      element.addEventListener('click', () => {
        this.state.detailsTab = element.dataset.detailsTab;
        this.renderNodeDetails();
      });
    });
    panel.querySelectorAll('[data-detail-node-run-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectConcreteNodeRun(element.dataset.detailNodeRunId));
    });
  }

  renderActivityPanel(projection) {
    const items = projection.activity.slice(0, 80);
    return `
      <section class="execution-activity-panel">
        ${items.length ? items.map((item) => `
          <div class="execution-activity-row">
            <time>${escapeHtml(this.formatTime(item.at))}</time>
            <span>${escapeHtml(item.label)}</span>
          </div>
        `).join('') : '<div class="muted-state compact">No activity yet.</div>'}
      </section>
    `;
  }

  renderNodeDetailsPanel(node, projection) {
    const nodeRuns = (projection.nodeRunsBySource.get(node.sourceNodeId) || []).slice().reverse();
    const inputPorts = projection.inputPortsByNode.get(node.sourceNodeId) || [];
    const outputPorts = projection.outputPortsByNode.get(node.sourceNodeId) || [];
    const selectedRun = this.selectedNodeRun();
    return `
      <div class="node-run-details-grid">
        ${this.detailRow('Agent', node.agentName || 'Unknown agent')}
        ${this.detailRow('Input mode', this.inputModeLabel(node.inputMode))}
      </div>
      <section class="execution-configured-ports">
        <h3>Inputs</h3>
        ${inputPorts.length ? inputPorts.map((port) => this.portDetail(port)).join('') : '<div class="muted-state compact">No configured inputs.</div>'}
        <h3>Outputs</h3>
        ${outputPorts.length ? outputPorts.map((port) => this.portDetail(port)).join('') : '<div class="muted-state compact">No configured outputs.</div>'}
      </section>
      <section class="execution-history-list">
        <h3>Execution history</h3>
        ${nodeRuns.length ? nodeRuns.map((nodeRun) => this.renderExecutionHistoryRow(nodeRun, projection)).join('') : '<div class="muted-state compact">No executions yet.</div>'}
      </section>
      ${selectedRun ? this.renderConcreteNodeRunDetails(selectedRun, projection) : ''}
      <details class="agent-snapshot-details">
        <summary>Agent snapshot</summary>
        <pre>${escapeHtml(node.agentInstructions || '')}</pre>
      </details>
    `;
  }

  portDetail(port) {
    return `
      <div class="execution-port-detail">
        <strong>${escapeHtml(port.name || 'Port')}</strong>
        <span>${escapeHtml(port.description || '')}</span>
      </div>
    `;
  }

  renderExecutionHistoryRow(nodeRun, projection) {
    const number = projection.invocationNumberById.get(nodeRun.id) || 1;
    const selected = nodeRun.id === this.state.selectedNodeRunId;
    return `
      <button class="execution-history-detail-row ${selected ? 'selected' : ''}" type="button" data-detail-node-run-id="${escapeHtml(nodeRun.id)}">
        <span>#${number}</span>
        <strong class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(nodeRun.status))}">${escapeHtml(nodeRun.status)}</strong>
        <small>${escapeHtml(this.durationLabel(nodeRun))}</small>
      </button>
    `;
  }

  renderConcreteNodeRunDetails(nodeRun, projection) {
    const inputPort = nodeRun.enteredViaInputPortId ? projection.portById.get(nodeRun.enteredViaInputPortId) : null;
    const selectedOutput = nodeRun.selectedOutputPortId ? projection.portById.get(nodeRun.selectedOutputPortId) : null;
    const consumed = (this.state.workflowRun.connectionResolutions || [])
      .filter((resolution) => resolution.resolutionType === 'DELIVERED' && resolution.consumedByNodeRunId === nodeRun.id);
    const outgoing = (this.state.workflowRun.connectionResolutions || [])
      .filter((resolution) => resolution.sourceNodeRunId === nodeRun.id);
    return `
      <section class="node-run-output">
        <h3>Execution</h3>
        <div class="node-run-details-grid">
          ${this.detailRow('Status', nodeRun.status || '-')}
          ${this.detailRow('Created', this.formatDate(nodeRun.createdAt))}
          ${this.detailRow('Started', this.formatDate(nodeRun.startedAt))}
          ${this.detailRow('Finished', this.formatDate(nodeRun.finishedAt))}
          ${this.detailRow('Duration', this.durationLabel(nodeRun))}
        </div>
      </section>
      <section class="node-run-output">
        <h3>Input</h3>
        ${this.detailRow(inputPort ? 'Entry input' : 'Entry', inputPort ? `${inputPort.name} · ${inputPort.description}` : 'Root task')}
        ${consumed.length ? consumed.map((resolution) => this.renderConsumedContribution(resolution, projection)).join('') : '<div class="muted-state compact">No consumed upstream contributions.</div>'}
      </section>
      <section class="node-run-output">
        <h3>Output</h3>
        ${nodeRun.output == null ? '<div class="muted-state compact">No output yet.</div>' : `<pre>${escapeHtml(this.formatOutput(nodeRun.output))}</pre>`}
      </section>
      <section class="node-run-output">
        <h3>Routing</h3>
        ${this.detailRow('Routing completed', this.formatDate(nodeRun.routingCompletedAt))}
        ${this.detailRow('Selected output', selectedOutput ? selectedOutput.name : '-')}
        ${outgoing.length ? outgoing.map((resolution) => this.renderRoutingFact(resolution, projection)).join('') : '<div class="muted-state compact">No routing facts yet.</div>'}
      </section>
      ${nodeRun.failure ? `
        <section class="node-run-failure">
          <h3>Failure</h3>
          <strong>${escapeHtml(nodeRun.failure.code || 'FAILURE')}</strong>
          <p>${escapeHtml(nodeRun.failure.message || '')}</p>
        </section>
      ` : ''}
    `;
  }

  renderConsumedContribution(resolution, projection) {
    const sourceRun = (this.state.workflowRun.nodeRuns || []).find((nodeRun) => nodeRun.id === resolution.sourceNodeRunId);
    const connection = projection.connectionById.get(resolution.sourceConnectionId);
    const sourcePort = connection ? projection.portById.get(connection.sourceOutputPortId) : null;
    const sourceNode = sourceRun ? projection.nodeBySource.get(sourceRun.sourceNodeId) : null;
    return `
      <div class="execution-routing-fact">
        <strong>${escapeHtml(sourceNode?.agentName || sourceRun?.agentName || 'Unknown source')} · ${escapeHtml(sourcePort?.name || 'Output')}</strong>
        ${resolution.payload == null ? '' : `<pre>${escapeHtml(this.formatOutput(resolution.payload))}</pre>`}
      </div>
    `;
  }

  renderRoutingFact(resolution, projection) {
    const connection = projection.connectionById.get(resolution.sourceConnectionId);
    const targetPort = connection ? projection.portById.get(connection.targetInputPortId) : null;
    const targetNode = targetPort ? projection.nodeBySource.get(targetPort.sourceNodeId) : null;
    return `
      <div class="execution-routing-fact">
        <strong>${escapeHtml(resolution.resolutionType || '-')} ${targetNode ? `→ ${targetNode.agentName}.${targetPort.name}` : ''}</strong>
        ${resolution.payload == null ? '' : `<pre>${escapeHtml(this.formatOutput(resolution.payload))}</pre>`}
      </div>
    `;
  }

  renderLegacyNodeDetails() {
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

  modernProjection() {
    const run = this.state.workflowRun || {};
    const graph = run.runtimeGraph || { nodes: [], ports: [], connections: [] };
    const nodeRuns = this.sortedNodeRuns(run.nodeRuns || []);
    const nodeBySource = new Map((graph.nodes || []).map((node) => [node.sourceNodeId, node]));
    const portById = new Map((graph.ports || []).map((port) => [port.sourcePortId, port]));
    const connectionById = new Map((graph.connections || []).map((connection) => [connection.sourceConnectionId, connection]));
    const inputPortsByNode = this.groupPorts(graph.ports || [], 'INPUT');
    const outputPortsByNode = this.groupPorts(graph.ports || [], 'OUTPUT');
    const nodeRunsBySource = new Map();
    const inputCountsByNode = new Map();
    const outputCountsByNode = new Map();
    const invocationNumberById = new Map();
    for (const nodeRun of nodeRuns) {
      const sourceId = nodeRun.sourceNodeId;
      if (!nodeRunsBySource.has(sourceId)) {
        nodeRunsBySource.set(sourceId, []);
      }
      const runs = nodeRunsBySource.get(sourceId);
      runs.push(nodeRun);
      invocationNumberById.set(nodeRun.id, runs.length);
      if (nodeRun.enteredViaInputPortId) {
        this.incrementNested(inputCountsByNode, sourceId, nodeRun.enteredViaInputPortId);
      }
      if (nodeRun.selectedOutputPortId) {
        this.incrementNested(outputCountsByNode, sourceId, nodeRun.selectedOutputPortId);
      }
    }
    const deliveredByConnection = new Map();
    const closedByConnection = new Map();
    for (const resolution of run.connectionResolutions || []) {
      if (resolution.resolutionType === 'DELIVERED') {
        deliveredByConnection.set(resolution.sourceConnectionId, (deliveredByConnection.get(resolution.sourceConnectionId) || 0) + 1);
      } else if (resolution.resolutionType === 'CLOSED') {
        closedByConnection.set(resolution.sourceConnectionId, (closedByConnection.get(resolution.sourceConnectionId) || 0) + 1);
      }
    }
    const activeNodeRuns = nodeRuns.filter((nodeRun) => nodeRun.status === 'RUNNING' || nodeRun.status === 'PENDING');
    const activity = this.projectActivity(run, { nodeBySource, portById, connectionById, invocationNumberById });
    const reached = new Set(nodeRuns.map((nodeRun) => nodeRun.sourceNodeId)).size;
    const summary = {
      running: nodeRuns.filter((nodeRun) => nodeRun.status === 'RUNNING').length,
      pending: nodeRuns.filter((nodeRun) => nodeRun.status === 'PENDING').length,
      routing: nodeRuns.filter((nodeRun) => nodeRun.status === 'SUCCEEDED' && !nodeRun.routingCompletedAt).length,
      totalNodeRuns: nodeRuns.length,
      reached,
      totalNodes: (graph.nodes || []).length,
      lastActivity: activity[0] || null
    };
    return {
      graph,
      nodeRuns,
      nodeBySource,
      portById,
      connectionById,
      inputPortsByNode,
      outputPortsByNode,
      nodeRunsBySource,
      inputCountsByNode,
      outputCountsByNode,
      deliveredByConnection,
      closedByConnection,
      activeNodeRuns,
      activity,
      invocationNumberById,
      summary
    };
  }

  projectActivity(run, indexes) {
    const events = [];
    let sequence = 0;
    const nodeRunById = new Map((run.nodeRuns || []).map((nodeRun) => [nodeRun.id, nodeRun]));
    const labelForRun = (nodeRun) => {
      const node = indexes.nodeBySource.get(nodeRun.sourceNodeId);
      const number = indexes.invocationNumberById.get(nodeRun.id) || 1;
      return `${node?.agentName || nodeRun.agentName || 'Unknown agent'} #${number}`;
    };
    for (const nodeRun of run.nodeRuns || []) {
      if (nodeRun.startedAt) {
        events.push({ at: nodeRun.startedAt, sequence: sequence += 1, label: `${labelForRun(nodeRun)} started` });
      }
      if (nodeRun.finishedAt) {
        events.push({ at: nodeRun.finishedAt, sequence: sequence += 1, label: `${labelForRun(nodeRun)} ${String(nodeRun.status || '').toLowerCase()}` });
      }
      if (nodeRun.selectedOutputPortId && (nodeRun.routingCompletedAt || nodeRun.finishedAt || nodeRun.startedAt || nodeRun.createdAt)) {
        const port = indexes.portById.get(nodeRun.selectedOutputPortId);
        events.push({ at: nodeRun.routingCompletedAt || nodeRun.finishedAt || nodeRun.startedAt || nodeRun.createdAt, sequence: sequence += 1, label: `${labelForRun(nodeRun)} selected ${port?.name || 'Output'}` });
      }
      if (nodeRun.routingCompletedAt) {
        events.push({ at: nodeRun.routingCompletedAt, sequence: sequence += 1, label: `${labelForRun(nodeRun)} routing completed` });
      }
    }
    for (const resolution of run.connectionResolutions || []) {
      if (resolution.resolutionType !== 'DELIVERED') {
        continue;
      }
      const sourceRun = nodeRunById.get(resolution.sourceNodeRunId);
      const connection = indexes.connectionById.get(resolution.sourceConnectionId);
      const sourcePort = connection ? indexes.portById.get(connection.sourceOutputPortId) : null;
      const targetPort = connection ? indexes.portById.get(connection.targetInputPortId) : null;
      const targetNode = targetPort ? indexes.nodeBySource.get(targetPort.sourceNodeId) : null;
      const sourceLabel = sourceRun ? labelForRun(sourceRun) : 'Unknown source';
      const output = sourcePort?.name || 'Output';
      const target = targetNode && targetPort ? `${targetNode.agentName}.${targetPort.name}` : resolution.resolutionType;
      events.push({ at: resolution.createdAt, sequence: sequence += 1, label: `${sourceLabel} → ${output} → ${target}` });
    }
    return events
      .filter((event) => Boolean(event.at))
      .sort((left, right) => {
        const leftTime = new Date(left.at).getTime();
        const rightTime = new Date(right.at).getTime();
        if (leftTime !== rightTime) {
          return rightTime - leftTime;
        }
        return right.sequence - left.sequence;
      });
  }

  detectPollAnimations(previous, next) {
    if (!this.hasRuntimeGraph(previous) || !this.hasRuntimeGraph(next)) {
      return this.emptyAnimations();
    }
    const previousRuns = new Map((previous.nodeRuns || []).map((nodeRun) => [nodeRun.id, nodeRun]));
    const previousResolutions = new Set((previous.connectionResolutions || []).map((resolution) => resolution.id));
    const animations = this.emptyAnimations();
    for (const nodeRun of next.nodeRuns || []) {
      const oldRun = previousRuns.get(nodeRun.id);
      if (!oldRun) {
        animations.nodeRunIds.add(nodeRun.id);
        if (nodeRun.enteredViaInputPortId) {
          animations.inputPortIds.add(nodeRun.enteredViaInputPortId);
        }
        if (nodeRun.selectedOutputPortId) {
          animations.outputPortIds.add(nodeRun.selectedOutputPortId);
        }
      } else {
        if (oldRun.status !== nodeRun.status && nodeRun.status === 'RUNNING') {
          animations.nodeRunIds.add(nodeRun.id);
        }
        if (!oldRun.selectedOutputPortId && nodeRun.selectedOutputPortId) {
          animations.outputPortIds.add(nodeRun.selectedOutputPortId);
        }
        if (oldRun.status !== 'FAILED' && nodeRun.status === 'FAILED') {
          animations.nodeRunIds.add(nodeRun.id);
        }
      }
    }
    for (const resolution of next.connectionResolutions || []) {
      if (!previousResolutions.has(resolution.id) && resolution.resolutionType === 'DELIVERED') {
        animations.connectionResolutionIds.add(resolution.id);
      }
    }
    return animations;
  }

  emptyAnimations() {
    return {
      nodeRunIds: new Set(),
      inputPortIds: new Set(),
      outputPortIds: new Set(),
      connectionResolutionIds: new Set()
    };
  }

  groupPorts(ports, direction) {
    const grouped = new Map();
    for (const port of ports.filter((item) => item.direction === direction)) {
      if (!grouped.has(port.sourceNodeId)) {
        grouped.set(port.sourceNodeId, []);
      }
      grouped.get(port.sourceNodeId).push(port);
    }
    for (const list of grouped.values()) {
      list.sort((left, right) => (left.order || 0) - (right.order || 0));
    }
    return grouped;
  }

  incrementNested(map, key, nestedKey) {
    if (!map.has(key)) {
      map.set(key, new Map());
    }
    const nested = map.get(key);
    nested.set(nestedKey, (nested.get(nestedKey) || 0) + 1);
  }

  sortedNodeRuns(nodeRuns) {
    return nodeRuns.slice().sort((left, right) => {
      const leftTime = this.parseTime(left.createdAt);
      const rightTime = this.parseTime(right.createdAt);
      if (leftTime !== rightTime) {
        return leftTime - rightTime;
      }
      return String(left.id || '').localeCompare(String(right.id || ''));
    });
  }

  latestNodeRunForSource(sourceNodeId, nodeRuns) {
    return this.sortedNodeRuns(nodeRuns.filter((nodeRun) => nodeRun.sourceNodeId === sourceNodeId)).at(-1) || null;
  }

  hasRuntimeGraph(workflowRun) {
    return Boolean(workflowRun?.runtimeGraph && Array.isArray(workflowRun.runtimeGraph.nodes));
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

  selectSourceNode(sourceNodeId) {
    this.state.selectedSourceNodeId = sourceNodeId;
    this.state.selectedNodeRunId = this.latestNodeRunForSource(sourceNodeId, this.state.workflowRun?.nodeRuns || [])?.id || null;
    this.renderGraph();
    this.renderNodeDetails();
  }

  selectConcreteNodeRun(nodeRunId) {
    const nodeRun = (this.state.workflowRun?.nodeRuns || []).find((item) => item.id === nodeRunId);
    if (nodeRun) {
      this.state.selectedSourceNodeId = nodeRun.sourceNodeId;
    }
    this.state.selectedNodeRunId = nodeRunId;
    this.renderGraph();
    this.renderNodeDetails();
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

  parseTime(value) {
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

  formatTime(value) {
    if (!value) {
      return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleTimeString();
  }

  elapsedLabel(run) {
    const start = this.parseTime(run.startedAt || run.createdAt);
    const end = run.finishedAt ? this.parseTime(run.finishedAt) : Date.now();
    if (!start || !end || end < start) {
      return '-';
    }
    const seconds = Math.floor((end - start) / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    return `${String(hours).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  }

  durationLabel(nodeRun) {
    if (!nodeRun.startedAt) {
      return '-';
    }
    const endValue = nodeRun.finishedAt || new Date().toISOString();
    const duration = Math.max(0, this.parseTime(endValue) - this.parseTime(nodeRun.startedAt));
    if (duration < 1000) {
      return `${duration}ms`;
    }
    return `${(duration / 1000).toFixed(duration < 10000 ? 1 : 0)}s`;
  }

  inputModeLabel(inputMode) {
    if (inputMode === 'TASK_AND_DEPENDENCIES') {
      return 'Original task + previous outputs';
    }
    if (inputMode === 'DEPENDENCIES_ONLY') {
      return 'Previous outputs only';
    }
    return inputMode || 'Unknown';
  }

  formatInputMode(nodeRun) {
    if (this.incomingExecutionEdges(nodeRun.id).length === 0) {
      return 'Original task';
    }
    return this.inputModeLabel(nodeRun.inputMode);
  }

  agentNameForSource(sourceNodeId) {
    return (this.state.workflowRun?.runtimeGraph?.nodes || []).find((node) => node.sourceNodeId === sourceNodeId)?.agentName;
  }

  modernNodeGeometry(node, projection) {
    const nodeRuns = projection.nodeRunsBySource.get(node.sourceNodeId) || [];
    const metricRows = 2
      + (nodeRuns.some((nodeRun) => nodeRun.status === 'RUNNING') ? 1 : 0)
      + (nodeRuns.some((nodeRun) => nodeRun.status === 'PENDING') ? 1 : 0)
      + (nodeRuns.some((nodeRun) => nodeRun.status === 'FAILED') ? 1 : 0)
      + (nodeRuns.some((nodeRun) => nodeRun.status === 'SUCCEEDED' && !nodeRun.routingCompletedAt) ? 1 : 0);
    let cursor = NODE_VERTICAL_PADDING + NODE_HEADER_HEIGHT + (metricRows * NODE_METRIC_ROW_HEIGHT);
    const inputPorts = projection.inputPortsByNode.get(node.sourceNodeId) || [];
    const outputPorts = projection.outputPortsByNode.get(node.sourceNodeId) || [];
    const sections = new Map();
    const portLayouts = new Map();
    for (const [side, ports] of [['input', inputPorts], ['output', outputPorts]]) {
      if (!ports.length) {
        continue;
      }
      cursor += NODE_SECTION_TOP_GAP;
      const top = cursor;
      const x = side === 'output' ? NODE_WIDTH : 0;
      ports.forEach((port, index) => {
        portLayouts.set(port.sourcePortId, {
          side,
          index,
          x,
          y: top + NODE_SECTION_LABEL_HEIGHT + (index * NODE_PORT_ROW_HEIGHT) + (NODE_PORT_ROW_HEIGHT / 2)
        });
      });
      cursor += NODE_SECTION_LABEL_HEIGHT + (ports.length * NODE_PORT_ROW_HEIGHT);
      sections.set(side, { top, rows: ports.length });
    }
    if (nodeRuns.length) {
      cursor += NODE_HISTORY_HEIGHT;
    }
    return {
      height: Math.max(NODE_MIN_HEIGHT, cursor + NODE_VERTICAL_PADDING),
      sections,
      ports: portLayouts
    };
  }

  modernPortLayoutFallback(port, node, geometry) {
    return {
      side: port.direction === 'OUTPUT' ? 'output' : 'input',
      index: 0,
      x: port.direction === 'OUTPUT' ? NODE_WIDTH : 0,
      y: geometry.height / 2
    };
  }

  modernPortPoint(port, projection) {
    const measured = this.elementCanvasCenter(this.elementByData('data-runtime-port-anchor-id', port.sourcePortId));
    if (measured) {
      return measured;
    }
    const node = projection.nodeBySource.get(port.sourceNodeId);
    if (!node) {
      return { x: 0, y: 0 };
    }
    const geometry = this.modernNodeGeometry(node, projection);
    const layout = geometry.ports.get(port.sourcePortId) || this.modernPortLayoutFallback(port, node, geometry);
    const x = Number(node.position?.x || 0) + layout.x;
    const y = Number(node.position?.y || 0) + layout.y;
    return { x, y };
  }

  prefersReducedMotion() {
    return Boolean(this.window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);
  }

  modernNodePoint(node, kind) {
    const projection = this.modernProjection();
    const geometry = this.modernNodeGeometry(node, projection);
    return {
      x: Number(node.position?.x || 0) + (kind === 'output' ? NODE_WIDTH : 0),
      y: Number(node.position?.y || 0) + (geometry.height / 2)
    };
  }

  legacyNodePoint(nodeRun, kind) {
    return {
      x: Number(nodeRun.position?.x || 0) + (kind === 'output' ? LEGACY_NODE_WIDTH : 0),
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
    this.disableFollowActive();
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
    this.disableFollowActive();
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

  elementByData(attribute, value) {
    if (!value) {
      return null;
    }
    return [...this.document.querySelectorAll(`[${attribute}]`)]
      .find((element) => element.getAttribute(attribute) === value) || null;
  }

  elementCanvasBounds(element) {
    if (!element?.getBoundingClientRect) {
      return null;
    }
    const rect = element.getBoundingClientRect();
    if (!rect) {
      return null;
    }
    const left = Number(rect.left);
    const top = Number(rect.top);
    const width = Number(rect.width ?? (Number(rect.right) - left));
    const height = Number(rect.height ?? (Number(rect.bottom) - top));
    if (![left, top, width, height].every(Number.isFinite) || (width === 0 && height === 0)) {
      return null;
    }
    const right = Number.isFinite(Number(rect.right)) ? Number(rect.right) : left + width;
    const bottom = Number.isFinite(Number(rect.bottom)) ? Number(rect.bottom) : top + height;
    const canvasRect = this.byId('agentsV2ExecutionCanvas')?.getBoundingClientRect?.() || { left: 0, top: 0 };
    const canvasLeft = Number(canvasRect.left) || 0;
    const canvasTop = Number(canvasRect.top) || 0;
    const scale = this.viewport.scale || 1;
    const canvasLeftPoint = ((left - canvasLeft) - this.viewport.x) / scale;
    const canvasTopPoint = ((top - canvasTop) - this.viewport.y) / scale;
    const canvasRightPoint = ((right - canvasLeft) - this.viewport.x) / scale;
    const canvasBottomPoint = ((bottom - canvasTop) - this.viewport.y) / scale;
    return {
      left: canvasLeftPoint,
      top: canvasTopPoint,
      right: canvasRightPoint,
      bottom: canvasBottomPoint,
      width: Math.max(0, canvasRightPoint - canvasLeftPoint),
      height: Math.max(0, canvasBottomPoint - canvasTopPoint)
    };
  }

  elementCanvasCenter(element) {
    const bounds = this.elementCanvasBounds(element);
    if (!bounds) {
      return null;
    }
    return {
      x: bounds.left + (bounds.width / 2),
      y: bounds.top + (bounds.height / 2)
    };
  }

  syncCanvasBounds(nodes, legacy = false, projection = null) {
    const edgesSvg = this.byId('agentsV2ExecutionEdges');
    const nodesLayer = this.byId('agentsV2ExecutionNodes');
    let width = MIN_CANVAS_WIDTH;
    let height = MIN_CANVAS_HEIGHT;
    if (legacy) {
      for (const node of nodes) {
        width = Math.max(width, Number(node.position?.x || 0) + LEGACY_NODE_WIDTH + CANVAS_PADDING);
        height = Math.max(height, Number(node.position?.y || 0) + LEGACY_NODE_HEIGHT + CANVAS_PADDING);
      }
    } else {
      const bounds = this.nodeBounds(nodes, projection || this.modernProjection(), 0);
      width = Math.max(width, bounds.left + bounds.width + CANVAS_PADDING);
      height = Math.max(height, bounds.top + bounds.height + CANVAS_PADDING);
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

  fitTopology(nodes = null) {
    const graphNodes = nodes || this.state.workflowRun?.runtimeGraph?.nodes || [];
    if (!graphNodes.length) {
      return;
    }
    const bounds = this.nodeBounds(graphNodes, this.hasRuntimeGraph(this.state.workflowRun) ? this.modernProjection() : null);
    const canvas = this.byId('agentsV2ExecutionCanvas');
    const rect = canvas?.getBoundingClientRect?.() || {};
    const viewportWidth = rect.width || 900;
    const viewportHeight = rect.height || 520;
    const scale = clamp(Math.min((viewportWidth - 48) / bounds.width, (viewportHeight - 48) / bounds.height), MIN_CANVAS_SCALE, MAX_CANVAS_SCALE);
    this.viewport = {
      scale,
      x: ((viewportWidth - bounds.width * scale) / 2) - (bounds.left * scale),
      y: ((viewportHeight - bounds.height * scale) / 2) - (bounds.top * scale)
    };
    this.applyViewportTransform();
  }

  centerActive() {
    const projection = this.modernProjection();
    const activeSourceIds = [...new Set(projection.activeNodeRuns.map((nodeRun) => nodeRun.sourceNodeId))];
    const activeNodes = activeSourceIds.map((sourceId) => projection.nodeBySource.get(sourceId)).filter(Boolean);
    if (activeNodes.length) {
      this.fitTopology(activeNodes);
    }
  }

  followNewActiveNode(animationDelta = null) {
    const delta = animationDelta || this.emptyAnimations();
    const newActiveIds = (this.state.workflowRun?.nodeRuns || [])
      .filter((nodeRun) => (nodeRun.status === 'RUNNING' || nodeRun.status === 'PENDING') && delta.nodeRunIds.has(nodeRun.id))
      .map((nodeRun) => nodeRun.sourceNodeId);
    if (!newActiveIds.length) {
      return;
    }
    const projection = this.modernProjection();
    const activeNodes = [...new Set(newActiveIds)].map((sourceId) => projection.nodeBySource.get(sourceId)).filter(Boolean);
    if (activeNodes.length) {
      this.panBoundsIntoViewport(this.nodeBounds(activeNodes, projection, 0));
    }
  }

  panBoundsIntoViewport(bounds) {
    const canvas = this.byId('agentsV2ExecutionCanvas');
    const rect = canvas?.getBoundingClientRect?.() || {};
    const viewportWidth = rect.width || 900;
    const viewportHeight = rect.height || 520;
    const scale = this.viewport.scale || 1;
    const visible = {
      left: -this.viewport.x / scale,
      top: -this.viewport.y / scale,
      right: (viewportWidth - this.viewport.x) / scale,
      bottom: (viewportHeight - this.viewport.y) / scale
    };
    let nextX = this.viewport.x;
    let nextY = this.viewport.y;
    const right = bounds.left + bounds.width;
    const bottom = bounds.top + bounds.height;
    if (bounds.width * scale > viewportWidth) {
      nextX = -bounds.left * scale;
    } else if (bounds.left < visible.left) {
      nextX += (visible.left - bounds.left) * scale;
    } else if (right > visible.right) {
      nextX += (visible.right - right) * scale;
    }
    if (bounds.height * scale > viewportHeight) {
      nextY = -bounds.top * scale;
    } else if (bounds.top < visible.top) {
      nextY += (visible.top - bounds.top) * scale;
    } else if (bottom > visible.bottom) {
      nextY += (visible.bottom - bottom) * scale;
    }
    if (nextX === this.viewport.x && nextY === this.viewport.y) {
      return;
    }
    this.viewport = { ...this.viewport, x: nextX, y: nextY };
    this.applyViewportTransform();
  }

  disableFollowActive() {
    if (this.state.followActive) {
      this.state.followActive = false;
      this.renderExecutionState();
    }
  }

  nodeBounds(nodes, projection = null, padding = 32) {
    let left = Infinity;
    let top = Infinity;
    let right = -Infinity;
    let bottom = -Infinity;
    for (const node of nodes) {
      const measured = projection
        ? this.elementCanvasBounds(this.elementByData('data-execution-source-node-id', node.sourceNodeId))
        : null;
      const x = measured ? measured.left : Number(node.position?.x || 0);
      const y = measured ? measured.top : Number(node.position?.y || 0);
      const width = measured ? measured.width : NODE_WIDTH;
      const height = measured ? measured.height : (projection ? this.modernNodeGeometry(node, projection).height : LEGACY_NODE_HEIGHT);
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x + width);
      bottom = Math.max(bottom, y + height);
    }
    return {
      left: left - padding,
      top: top - padding,
      width: Math.max(1, right - left + (padding * 2)),
      height: Math.max(1, bottom - top + (padding * 2))
    };
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
      selectedSourceNodeId: null,
      detailsTab: 'details',
      followActive: false,
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

function statusSymbol(status) {
  if (status === 'SUCCEEDED') {
    return '✓';
  }
  if (status === 'RUNNING') {
    return '●';
  }
  if (status === 'PENDING') {
    return '○';
  }
  if (status === 'FAILED') {
    return '!';
  }
  if (status === 'BLOCKED') {
    return 'B';
  }
  if (status === 'CANCELLED') {
    return '×';
  }
  return '?';
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
