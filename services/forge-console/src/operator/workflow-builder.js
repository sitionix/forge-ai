import { escapeHtml } from './dom-render-helpers.js';
const NODE_WIDTH = 252;
const NODE_MIN_HEIGHT = 132;
const NODE_MID_Y = 55;
const NODE_PORT_ROW_HEIGHT = 26;
const NODE_HEIGHT_PADDING = 58;
const NODE_START_X = 120;
const NODE_START_Y = 90;
const TASK_INPUT_X = 26;
const TASK_INPUT_Y = 42;
const TASK_INPUT_WIDTH = 104;
const TASK_INPUT_HEIGHT = 44;
const NODE_HORIZONTAL_STEP = 360;
const NODE_VERTICAL_STEP = 160;
const MIN_CANVAS_WIDTH = 1600;
const MIN_CANVAS_HEIGHT = 1000;
const NODE_PORT_LABEL_EXTENT = 24;
const CANVAS_PADDING = 240;
const MIN_CANVAS_SCALE = 0.45;
const MAX_CANVAS_SCALE = 1.8;
const DEFAULT_INPUT_MODE = 'DEPENDENCIES_ONLY';
const TASK_AND_DEPENDENCIES_INPUT_MODE = 'TASK_AND_DEPENDENCIES';
const NODE_DRAG_THRESHOLD = 3;
const DEFAULT_INPUT_PORT = {
  name: 'Input',
  description: 'Default workflow input.',
  order: 0
};
const DEFAULT_OUTPUT_PORT = {
  name: 'Output',
  description: 'Default workflow output.',
  order: 0
};

export class WorkflowBuilder {
  constructor(options) {
    this.document = options.document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api;
    this.onBack = options.onBack;
    this.onSaved = options.onSaved;
    this.workflow = null;
    this.project = null;
    this.agents = [];
    this.nodeDrag = null;
    this.canvasPan = null;
    this.connectionDrag = null;
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.nodeEditorNodeId = null;
    this.nodeEditorDraft = null;
    this.nodeEditorEditingPortKey = null;
    this.saving = false;
  }

  bind() {
    this.handlePointerMove = (event) => this.onPointerMove(event);
    this.handlePointerUp = (event) => this.onPointerUp(event);
    this.handlePointerCancel = () => {
      this.cancelConnectionDrag();
      this.canvasPan = null;
      this.byId('agentsV2WorkflowCanvas')?.classList.remove('panning');
    };
    this.handleCanvasPointerDown = (event) => this.onCanvasPointerDown(event);
    this.handleCanvasWheel = (event) => this.onCanvasWheel(event);
    this.handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        this.cancelConnectionDrag();
      }
    };
    this.document.addEventListener('pointermove', this.handlePointerMove);
    this.document.addEventListener('pointerup', this.handlePointerUp);
    this.document.addEventListener('pointercancel', this.handlePointerCancel);
    this.document.addEventListener('keydown', this.handleKeyDown);
    this.byId('agentsV2WorkflowCanvas')?.addEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2WorkflowCanvas')?.addEventListener('wheel', this.handleCanvasWheel, { passive: false });
    this.byId('agentsV2BuilderBack')?.addEventListener('click', () => this.onBack());
    this.byId('agentsV2WorkflowSave')?.addEventListener('click', () => this.save());
    this.byId('agentsV2NodeEditorCancel')?.addEventListener('click', () => this.closeNodeEditor());
    this.byId('agentsV2NodeEditorClose')?.addEventListener('click', () => this.closeNodeEditor());
    this.byId('agentsV2NodeEditorSave')?.addEventListener('click', () => this.saveNodeEditor());
    this.byId('agentsV2NodeEditorBody')?.addEventListener('click', (event) => this.onNodeEditorClick(event));
  }

  dispose() {
    this.document.removeEventListener('pointermove', this.handlePointerMove);
    this.document.removeEventListener('pointerup', this.handlePointerUp);
    this.document.removeEventListener('pointercancel', this.handlePointerCancel);
    this.document.removeEventListener('keydown', this.handleKeyDown);
    this.byId('agentsV2WorkflowCanvas')?.removeEventListener('pointerdown', this.handleCanvasPointerDown);
    this.byId('agentsV2WorkflowCanvas')?.removeEventListener('wheel', this.handleCanvasWheel);
  }

  open(workflow, project, agents) {
    this.workflow = this.cloneWorkflow(workflow);
    this.project = project;
    this.agents = agents || [];
    this.nodeDrag = null;
    this.canvasPan = null;
    this.connectionDrag = null;
    this.viewport = { x: 0, y: 0, scale: 1 };
    this.nodeEditorNodeId = null;
    this.nodeEditorDraft = null;
    this.nodeEditorEditingPortKey = null;
    this.showError('');
    this.byId('agentsV2BuilderTitle').textContent = workflow.name;
    this.byId('agentsV2BuilderCrumbs').textContent = `Projects / ${project?.name || ''} / Workflows / ${workflow.name}`;
    this.render();
  }

  close() {
    this.workflow = null;
    this.project = null;
    this.nodeDrag = null;
    this.canvasPan = null;
    this.connectionDrag = null;
    this.closeNodeEditor();
    this.clearConnectionTargetClasses();
  }

  setAgents(agents) {
    this.agents = agents || [];
    if (this.workflow) {
      this.renderPalette();
      this.renderNodes();
      this.renderEdges();
    }
  }

  addNode(agentId) {
    if (!this.workflow) {
      return;
    }
    this.showError('');
    let nodeId;
    let inputPortId;
    let outputPortId;
    try {
      nodeId = this.randomUuid();
      inputPortId = this.randomUuid();
      outputPortId = this.randomUuid();
    } catch (error) {
      this.showError(error.message || 'Node UUID generation unavailable.');
      return;
    }
    if (this.workflow.nodes.some((node) => node.id === nodeId) || this.portById(inputPortId) || this.portById(outputPortId)) {
      this.showError('Node UUID generation produced a duplicate ID.');
      return;
    }
    const index = this.workflow.nodes.length;
    this.workflow.nodes.push({
      id: nodeId,
      targetId: agentId,
      inputMode: DEFAULT_INPUT_MODE,
      inputs: [{ id: inputPortId, ...DEFAULT_INPUT_PORT }],
      outputs: [{ id: outputPortId, ...DEFAULT_OUTPUT_PORT }],
      position: {
        x: NODE_START_X + (index % 3) * NODE_HORIZONTAL_STEP,
        y: NODE_START_Y + Math.floor(index / 3) * NODE_VERTICAL_STEP
      }
    });
    this.render();
  }

  removeNode(nodeId) {
    if (!this.workflow) {
      return;
    }
    const node = this.workflow.nodes.find((candidate) => candidate.id === nodeId);
    const portIds = new Set([...this.nodePorts(node?.inputs), ...this.nodePorts(node?.outputs)].map((port) => port.id));
    this.workflow.nodes = this.workflow.nodes.filter((candidate) => candidate.id !== nodeId);
    this.workflow.connections = this.workflowConnections()
      .filter((connection) => !portIds.has(connection.sourceOutputPortId) && !portIds.has(connection.targetInputPortId));
    if (portIds.has(this.workflow.taskInputPortId)) {
      this.workflow.taskInputPortId = null;
    }
    if (this.connectionDrag && portIds.has(this.connectionDrag.sourceOutputPortId)) {
      this.cancelConnectionDrag();
    }
    this.render();
  }

  removeConnection(connectionId) {
    this.workflow.connections = this.workflowConnections().filter((connection) => connection.id !== connectionId);
    this.render();
  }

  render() {
    if (!this.workflow) {
      return;
    }
    this.renderPalette();
    this.renderNodes();
    this.renderEdges();
  }

  renderPalette() {
    const palette = this.byId('agentsV2WorkflowAgentList');
    palette.innerHTML = this.agents.map((agent) => `
      <button class="agents-v2-agent-palette-row" type="button" data-add-agent-id="${escapeHtml(agent.id)}">
        ${escapeHtml(agent.name)}
      </button>
    `).join('') || '<div class="muted-state">No agents yet.</div>';
    palette.querySelectorAll('[data-add-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.addNode(element.dataset.addAgentId));
    });
  }

  renderNodes() {
    const nodesLayer = this.byId('agentsV2WorkflowNodes');
    const nodes = this.workflow?.nodes || [];
    nodesLayer.innerHTML = nodes.map((node) => this.renderNode(node)).join('');
    nodesLayer.insertAdjacentHTML('afterbegin', this.renderTaskInput());
    nodesLayer.querySelectorAll('[data-node-id]').forEach((element) => {
      element.addEventListener('pointerdown', (event) => this.onNodePointerDown(event, element.dataset.nodeId));
    });
    nodesLayer.querySelectorAll('[data-node-remove]').forEach((element) => {
      element.addEventListener('click', () => this.removeNode(element.dataset.nodeRemove));
    });
    nodesLayer.querySelectorAll('[data-node-output-port]').forEach((element) => {
      element.addEventListener('pointerdown', (event) => this.startConnectionDrag(event, element.dataset.nodeOutputPort));
    });
    nodesLayer.querySelector('[data-task-input-output]')?.addEventListener('pointerdown', (event) => this.startTaskInputDrag(event));
  }

  renderTaskInput() {
    return `
      <article class="workflow-task-input" data-task-input style="left:${TASK_INPUT_X}px; top:${TASK_INPUT_Y}px;">
        <span>TASK INPUT</span>
        <button class="node-handle output" type="button" title="Task input" data-task-input-output aria-label="Task input"></button>
      </article>
    `;
  }

  renderNode(node) {
    const agent = this.agentById(node.targetId);
    const inputs = this.nodePorts(node.inputs);
    const outputs = this.nodePorts(node.outputs);
    const portRows = Math.max(inputs.length, outputs.length, 1);
    return `
      <article class="workflow-node" data-node-id="${escapeHtml(node.id)}" style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px; --workflow-node-port-rows:${portRows};">
        <div class="workflow-node-port-list input" aria-label="Configured inputs">
          ${this.renderCompactPorts(inputs, 'input')}
        </div>
        <div class="workflow-node-content">
          <strong>${escapeHtml(agent?.name || 'Unknown agent')}</strong>
          <span>${escapeHtml(agent?.instructions || 'Reusable agent')}</span>
        </div>
        <button class="node-delete" type="button" title="Remove node" data-node-remove="${escapeHtml(node.id)}" aria-label="Remove node">×</button>
        <div class="workflow-node-port-list output" aria-label="Configured outputs">
          ${this.renderCompactPorts(outputs, 'output')}
        </div>
      </article>
    `;
  }

  renderCompactPorts(ports, direction) {
    return ports.map((port) => `
      <div class="workflow-node-port" title="${escapeHtml(port.name)}">
        ${direction === 'input' ? `<button class="node-handle input" type="button" title="${escapeHtml(port.name)}" data-node-input-port="${escapeHtml(port.id)}" aria-label="Input port ${escapeHtml(port.name)}"></button>` : ''}
        <span>${escapeHtml(port.name)}</span>
        ${direction === 'output' ? `<button class="node-handle output" type="button" title="${escapeHtml(port.name)}" data-node-output-port="${escapeHtml(port.id)}" aria-label="Output port ${escapeHtml(port.name)}"></button>` : ''}
      </div>
    `).join('');
  }

  renderEdges() {
    const svg = this.byId('agentsV2WorkflowEdges');
    this.syncCanvasBounds();
    const groups = [];
    for (const connection of this.workflowConnections()) {
      if (!this.portById(connection.sourceOutputPortId) || !this.portById(connection.targetInputPortId)) {
        continue;
      }
      const start = this.connectorPoint(connection.sourceOutputPortId, 'output');
      const end = this.connectorPoint(connection.targetInputPortId, 'input');
      const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
      groups.push(`
        <g class="workflow-edge" data-edge-id="${escapeHtml(connection.id)}" data-edge-source-port="${escapeHtml(connection.sourceOutputPortId)}" data-edge-target-port="${escapeHtml(connection.targetInputPortId)}">
          <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2Arrow)" />
          <path class="edge-hit" d="${this.pathD(start, end)}" />
          <circle class="edge-remove" data-remove-connection="${escapeHtml(connection.id)}" cx="${midpoint.x}" cy="${midpoint.y}" r="10" />
          <text class="edge-remove-label" data-remove-connection="${escapeHtml(connection.id)}" x="${midpoint.x}" y="${midpoint.y + 4}">×</text>
        </g>
      `);
    }
    if (this.workflow?.taskInputPortId && this.portById(this.workflow.taskInputPortId, 'inputs')) {
      const start = this.taskInputConnectorPoint();
      const end = this.connectorPoint(this.workflow.taskInputPortId, 'input');
      const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
      groups.push(`
        <g class="workflow-edge task-input-edge" data-task-input-edge data-edge-target-port="${escapeHtml(this.workflow.taskInputPortId)}">
          <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2Arrow)" />
          <path class="edge-hit" d="${this.pathD(start, end)}" />
          <circle class="edge-remove" data-remove-task-input cx="${midpoint.x}" cy="${midpoint.y}" r="10" />
          <text class="edge-remove-label" data-remove-task-input x="${midpoint.x}" y="${midpoint.y + 4}">×</text>
        </g>
      `);
    }
    const preview = this.connectionDrag
      ? `<path class="workflow-edge-preview" d="${this.pathD(this.connectionDrag.start, this.connectionDrag.current)}" marker-end="url(#agentsV2Arrow)" />`
      : '';
    svg.innerHTML = `
      <defs>
        <marker id="agentsV2Arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      ${groups.join('')}
      ${preview}
    `;
    svg.querySelectorAll('[data-remove-connection]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.removeConnection(element.dataset.removeConnection);
      });
    });
    svg.querySelectorAll('[data-remove-task-input]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.removeTaskInput();
      });
    });
  }

  onNodePointerDown(event, nodeId) {
    if (event.target.closest('button, select, .node-handle')) {
      return;
    }
    const node = this.workflow?.nodes.find((candidate) => candidate.id === nodeId);
    if (!node) {
      return;
    }
    this.nodeDrag = {
      nodeId,
      startX: event.clientX,
      startY: event.clientY,
      originalX: Number(node.position?.x || 0),
      originalY: Number(node.position?.y || 0),
      moved: false
    };
    this.byNodeId(nodeId)?.classList.add('dragging');
  }

  startConnectionDrag(event, sourceOutputPortId) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.portById(sourceOutputPortId, 'outputs')) {
      return;
    }
    const start = this.connectorPoint(sourceOutputPortId, 'output');
    this.connectionDrag = { sourceOutputPortId, start, current: start };
    this.applyConnectionTargetClasses();
    this.renderEdges();
  }

  startTaskInputDrag(event) {
    event.preventDefault();
    event.stopPropagation();
    const start = this.taskInputConnectorPoint();
    this.connectionDrag = { taskInput: true, start, current: start };
    this.applyConnectionTargetClasses();
    this.renderEdges();
  }

  onPointerMove(event) {
    if (this.nodeDrag) {
      this.moveNode(event);
      return;
    }
    if (this.connectionDrag) {
      this.connectionDrag.current = this.canvasPoint(event);
      this.updatePreviewPath();
      this.updateHoveredInput(event);
      return;
    }
    if (this.canvasPan) {
      this.moveCanvas(event);
    }
  }

  onPointerUp(event) {
    if (this.nodeDrag) {
      const nodeId = this.nodeDrag.nodeId;
      const openedEditor = !this.nodeDrag.moved;
      this.byNodeId(this.nodeDrag.nodeId)?.classList.remove('dragging');
      this.nodeDrag = null;
      if (openedEditor) {
        this.openNodeEditor(nodeId);
      }
      return;
    }
    if (this.canvasPan) {
      this.byId('agentsV2WorkflowCanvas')?.classList.remove('panning');
      this.canvasPan = null;
      return;
    }
    if (!this.connectionDrag) {
      return;
    }
    const targetInputPortId = this.inputPortIdFromEvent(event);
    if (this.connectionDrag.taskInput && targetInputPortId && this.canConnectTaskInput(targetInputPortId)) {
      this.workflow.taskInputPortId = targetInputPortId;
      this.connectionDrag = null;
      this.clearConnectionTargetClasses();
      this.render();
      return;
    }
    if (targetInputPortId && this.canConnect(this.connectionDrag.sourceOutputPortId, targetInputPortId)) {
      let connectionId;
      try {
        connectionId = this.randomUuid();
      } catch (error) {
        this.showError(error.message || 'Connection UUID generation unavailable.');
        this.cancelConnectionDrag();
        return;
      }
      this.workflow.connections = [
        ...this.workflowConnections(),
        {
          id: connectionId,
          sourceOutputPortId: this.connectionDrag.sourceOutputPortId,
          targetInputPortId
        }
      ];
      this.connectionDrag = null;
      this.clearConnectionTargetClasses();
      this.render();
      return;
    }
    this.cancelConnectionDrag();
  }

  moveNode(event) {
    const node = this.workflow?.nodes.find((candidate) => candidate.id === this.nodeDrag.nodeId);
    if (!node) {
      return;
    }
    const deltaX = event.clientX - this.nodeDrag.startX;
    const deltaY = event.clientY - this.nodeDrag.startY;
    if (!this.nodeDrag.moved && Math.max(Math.abs(deltaX), Math.abs(deltaY)) < NODE_DRAG_THRESHOLD) {
      return;
    }
    this.nodeDrag.moved = true;
    node.position = {
      x: Math.max(0, this.nodeDrag.originalX + (deltaX / this.viewport.scale)),
      y: Math.max(0, this.nodeDrag.originalY + (deltaY / this.viewport.scale))
    };
    const element = this.byNodeId(node.id);
    if (element) {
      element.style.left = `${node.position.x}px`;
      element.style.top = `${node.position.y}px`;
    }
    this.syncCanvasBounds();
    this.updateConnectedEdges(node.id);
  }

  onCanvasPointerDown(event) {
    if (event.button !== 0 || this.nodeDrag || this.connectionDrag) {
      return;
    }
    if (event.target?.closest?.('.workflow-node, .workflow-task-input, .workflow-edge, button, select, input, textarea')) {
      return;
    }
    event.preventDefault();
    this.canvasPan = {
      startX: event.clientX,
      startY: event.clientY,
      originalX: this.viewport.x,
      originalY: this.viewport.y
    };
    this.byId('agentsV2WorkflowCanvas')?.classList.add('panning');
  }

  moveCanvas(event) {
    this.viewport = {
      ...this.viewport,
      x: this.canvasPan.originalX + (event.clientX - this.canvasPan.startX),
      y: this.canvasPan.originalY + (event.clientY - this.canvasPan.startY)
    };
    this.applyViewportTransform();
  }

  onCanvasWheel(event) {
    if (!this.workflow) {
      return;
    }
    event.preventDefault();
    const canvas = this.byId('agentsV2WorkflowCanvas');
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

  updateConnectedEdges(nodeId) {
    const svg = this.byId('agentsV2WorkflowEdges');
    const portIds = new Set([
      ...this.nodePorts(this.workflow?.nodes.find((node) => node.id === nodeId)?.inputs),
      ...this.nodePorts(this.workflow?.nodes.find((node) => node.id === nodeId)?.outputs)
    ].map((port) => port.id));
    svg.querySelectorAll('.workflow-edge').forEach((group) => {
      if (portIds.has(group.dataset.edgeSourcePort) || portIds.has(group.dataset.edgeTargetPort)) {
        this.updateEdgeGroup(group);
      }
    });
  }

  updateEdgeGroup(group) {
    const sourcePort = this.portById(group.dataset.edgeSourcePort, 'outputs');
    const targetPort = this.portById(group.dataset.edgeTargetPort, 'inputs');
    if (!targetPort) {
      return;
    }
    const start = sourcePort ? this.connectorPoint(sourcePort.port.id, 'output') : this.taskInputConnectorPoint();
    const end = this.connectorPoint(targetPort.port.id, 'input');
    const path = this.pathD(start, end);
    group.querySelectorAll('.edge-visible, .edge-hit').forEach((element) => {
      element.setAttribute('d', path);
    });
    const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
    group.querySelector('.edge-remove')?.setAttribute('cx', midpoint.x);
    group.querySelector('.edge-remove')?.setAttribute('cy', midpoint.y);
    group.querySelector('.edge-remove-label')?.setAttribute('x', midpoint.x);
    group.querySelector('.edge-remove-label')?.setAttribute('y', midpoint.y + 4);
  }

  cancelConnectionDrag() {
    if (!this.connectionDrag) {
      return;
    }
    this.connectionDrag = null;
    this.clearConnectionTargetClasses();
    this.renderEdges();
  }

  canConnect(sourceOutputPortId, targetInputPortId) {
    if (!this.workflow || sourceOutputPortId === targetInputPortId) {
      return false;
    }
    const source = this.portById(sourceOutputPortId, 'outputs');
    const target = this.portById(targetInputPortId, 'inputs');
    return Boolean(
      source &&
      target &&
      source.node.id !== target.node.id &&
      !this.workflowConnections().some((connection) =>
        connection.sourceOutputPortId === sourceOutputPortId && connection.targetInputPortId === targetInputPortId
      )
    );
  }

  canConnectTaskInput(targetInputPortId) {
    return Boolean(this.workflow && this.portById(targetInputPortId, 'inputs'));
  }

  removeTaskInput() {
    if (!this.workflow) {
      return;
    }
    this.workflow.taskInputPortId = null;
    this.render();
  }

  openNodeEditor(nodeId) {
    const node = this.workflow?.nodes.find((candidate) => candidate.id === nodeId);
    if (!node) {
      return;
    }
    this.nodeEditorNodeId = nodeId;
    this.nodeEditorDraft = this.cloneNode(node);
    this.nodeEditorEditingPortKey = null;
    this.renderNodeEditor();
    this.showNodeEditorError('');
    const dialog = this.byId('agentsV2NodeEditorDialog');
    if (dialog?.showModal) {
      dialog.showModal();
    } else {
      dialog?.setAttribute('open', 'open');
    }
  }

  closeNodeEditor() {
    this.nodeEditorNodeId = null;
    this.nodeEditorDraft = null;
    this.nodeEditorEditingPortKey = null;
    this.showNodeEditorError('');
    const dialog = this.byId('agentsV2NodeEditorDialog');
    if (dialog?.close) {
      dialog.close();
    } else {
      dialog?.removeAttribute('open');
    }
  }

  renderNodeEditor() {
    const node = this.nodeEditorDraft;
    if (!node) {
      return;
    }
    const agent = this.agentById(node.targetId);
    this.byId('agentsV2NodeEditorTitle').textContent = agent?.name || 'Unknown agent';
    this.byId('agentsV2NodeEditorAgent').textContent = `Agent: ${agent?.name || 'Unknown agent'}`;
    this.byId('agentsV2NodeEditorBody').innerHTML = `
      <div class="node-editor-port-columns">
        ${this.renderNodeEditorPorts('inputs', 'INPUTS', '+ Add Input')}
        ${this.renderNodeEditorPorts('outputs', 'OUTPUTS', '+ Add Output')}
      </div>
      <div class="node-editor-input-mode">
        <label class="field-label" for="agentsV2NodeEditorInputMode">Input content</label>
        ${this.renderNodeEditorInputMode(node)}
      </div>
    `;
  }

  renderNodeEditorPorts(direction, title, addLabel) {
    const ports = this.nodePorts(this.nodeEditorDraft?.[direction]);
    return `
      <section class="node-editor-port-section" data-node-editor-direction="${direction}">
        <div class="node-editor-section-head">
          <h3>${escapeHtml(title)}</h3>
          <button class="button tiny secondary" type="button" data-node-editor-add="${direction}">${escapeHtml(addLabel)}</button>
        </div>
        <div class="node-editor-port-list">
          ${ports.map((port, index) => this.renderNodeEditorPort(direction, port, index)).join('') || '<div class="muted-state compact">No ports configured.</div>'}
        </div>
      </section>
    `;
  }

  renderNodeEditorPort(direction, port, index) {
    if (!this.isNodeEditorPortEditing(direction, port.id)) {
      return this.renderNodeEditorCompactPort(direction, port);
    }
    return `
      <div class="node-editor-port-row editing" data-node-editor-port="${escapeHtml(port.id)}" data-node-editor-port-direction="${escapeHtml(direction)}">
        <div class="node-editor-port-row-head">
          <strong>${index + 1}.</strong>
          <button class="node-editor-port-remove" type="button" title="Remove port" aria-label="Remove port" data-node-editor-remove="${escapeHtml(port.id)}" data-node-editor-remove-direction="${escapeHtml(direction)}">×</button>
        </div>
        <label class="field-label" for="node-editor-${escapeHtml(direction)}-${escapeHtml(port.id)}-name">Name</label>
        <input id="node-editor-${escapeHtml(direction)}-${escapeHtml(port.id)}-name" class="text-input" type="text" value="${escapeHtml(port.name || '')}" data-node-editor-port-name>
        <label class="field-label" for="node-editor-${escapeHtml(direction)}-${escapeHtml(port.id)}-description">Description</label>
        <textarea id="node-editor-${escapeHtml(direction)}-${escapeHtml(port.id)}-description" class="agent-modal-textarea node-editor-description" data-node-editor-port-description>${escapeHtml(port.description || '')}</textarea>
      </div>
    `;
  }

  renderNodeEditorCompactPort(direction, port) {
    return `
      <div class="node-editor-port-row compact" data-node-editor-port="${escapeHtml(port.id)}" data-node-editor-port-direction="${escapeHtml(direction)}" data-node-editor-edit="${escapeHtml(port.id)}" data-node-editor-edit-direction="${escapeHtml(direction)}">
        <div class="node-editor-port-summary">
          <strong>${escapeHtml(port.name || 'Untitled port')}</strong>
          <p>${escapeHtml(port.description || '')}</p>
        </div>
        <div class="node-editor-port-actions">
          <button class="button tiny secondary" type="button" data-node-editor-edit="${escapeHtml(port.id)}" data-node-editor-edit-direction="${escapeHtml(direction)}">Edit</button>
          <button class="node-editor-port-remove" type="button" title="Remove port" aria-label="Remove port" data-node-editor-remove="${escapeHtml(port.id)}" data-node-editor-remove-direction="${escapeHtml(direction)}">×</button>
        </div>
      </div>
    `;
  }

  renderNodeEditorInputMode(node) {
    const inputPortIds = new Set(this.nodePorts(node.inputs).map((port) => port.id));
    const hasDependencies = this.workflowConnections().some((connection) => inputPortIds.has(connection.targetInputPortId));
    const hasTaskInput = inputPortIds.has(this.workflow?.taskInputPortId);
    if (hasTaskInput && !hasDependencies) {
      return '<div class="node-editor-readonly-input" data-node-editor-root-input>Original task</div>';
    }
    const inputMode = this.nodeInputMode(node);
    return `
      <select id="agentsV2NodeEditorInputMode" class="text-input" data-node-editor-input-mode>
        <option value="${DEFAULT_INPUT_MODE}" ${inputMode === DEFAULT_INPUT_MODE ? 'selected' : ''}>Dependencies only</option>
        <option value="${TASK_AND_DEPENDENCIES_INPUT_MODE}" ${inputMode === TASK_AND_DEPENDENCIES_INPUT_MODE ? 'selected' : ''}>Task + dependencies</option>
      </select>
    `;
  }

  onNodeEditorClick(event) {
    const addButton = event.target.closest?.('[data-node-editor-add]');
    if (addButton) {
      this.addNodeEditorPort(addButton.dataset.nodeEditorAdd);
      return;
    }
    const removeButton = event.target.closest?.('[data-node-editor-remove]');
    if (removeButton) {
      this.removeNodeEditorPort(removeButton.dataset.nodeEditorRemoveDirection, removeButton.dataset.nodeEditorRemove);
      return;
    }
    const editTarget = event.target.closest?.('[data-node-editor-edit]');
    if (editTarget) {
      this.editNodeEditorPort(editTarget.dataset.nodeEditorEditDirection, editTarget.dataset.nodeEditorEdit);
    }
  }

  addNodeEditorPort(direction) {
    if (!this.nodeEditorDraft || !['inputs', 'outputs'].includes(direction)) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    let id;
    try {
      id = this.randomUuid();
    } catch (error) {
      this.showNodeEditorError(error.message || 'Port UUID generation unavailable.');
      return;
    }
    const ports = this.nodePorts(this.nodeEditorDraft[direction]);
    ports.push({ id, name: '', description: '', order: ports.length });
    this.nodeEditorDraft[direction] = ports;
    this.nodeEditorEditingPortKey = this.nodeEditorPortKey(direction, id);
    this.renderNodeEditor();
  }

  removeNodeEditorPort(direction, portId) {
    if (!this.nodeEditorDraft || !['inputs', 'outputs'].includes(direction)) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    if (this.isPortConnected(portId)) {
      this.showNodeEditorError('Port is connected. Remove its connections first.');
      return;
    }
    this.nodeEditorDraft[direction] = this.reindexPorts(
      this.nodePorts(this.nodeEditorDraft[direction]).filter((port) => port.id !== portId)
    );
    if (this.nodeEditorEditingPortKey === this.nodeEditorPortKey(direction, portId)) {
      this.nodeEditorEditingPortKey = null;
    }
    this.renderNodeEditor();
  }

  editNodeEditorPort(direction, portId) {
    if (!this.nodeEditorDraft || !['inputs', 'outputs'].includes(direction)) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    this.nodeEditorEditingPortKey = this.nodeEditorPortKey(direction, portId);
    this.renderNodeEditor();
  }

  syncNodeEditorDraftFromDom() {
    if (!this.nodeEditorDraft) {
      return;
    }
    for (const direction of ['inputs', 'outputs']) {
      const portsById = new Map(this.nodePorts(this.nodeEditorDraft[direction]).map((port) => [port.id, { ...port }]));
      this.byId('agentsV2NodeEditorBody').querySelectorAll(`[data-node-editor-port-direction="${direction}"]`).forEach((row) => {
        const port = portsById.get(row.dataset.nodeEditorPort);
        if (!port) {
          return;
        }
        const nameInput = row.querySelector('[data-node-editor-port-name]');
        const descriptionInput = row.querySelector('[data-node-editor-port-description]');
        if (nameInput) {
          port.name = nameInput.value || '';
        }
        if (descriptionInput) {
          port.description = descriptionInput.value || '';
        }
      });
      this.nodeEditorDraft[direction] = this.reindexPorts([...portsById.values()]);
    }
    const inputMode = this.byId('agentsV2NodeEditorBody').querySelector('[data-node-editor-input-mode]')?.value;
    if (inputMode) {
      this.nodeEditorDraft.inputMode = this.normalizeInputMode(inputMode);
    }
  }

  saveNodeEditor() {
    if (!this.workflow || !this.nodeEditorDraft || !this.nodeEditorNodeId) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    const validation = this.validateNodeEditorDraft();
    if (validation) {
      this.showNodeEditorError(validation);
      return;
    }
    const savedNode = {
      ...this.nodeEditorDraft,
      inputMode: this.nodeInputMode(this.nodeEditorDraft),
      inputs: this.reindexPorts(this.nodeEditorDraft.inputs).map((port) => this.normalizedPort(port)),
      outputs: this.reindexPorts(this.nodeEditorDraft.outputs).map((port) => this.normalizedPort(port))
    };
    this.workflow.nodes = this.workflow.nodes.map((node) => node.id === this.nodeEditorNodeId ? savedNode : node);
    this.closeNodeEditor();
    this.render();
  }

  validateNodeEditorDraft() {
    for (const [direction, label] of [['inputs', 'Input'], ['outputs', 'Output']]) {
      const names = new Set();
      for (const port of this.nodePorts(this.nodeEditorDraft[direction])) {
        const name = String(port.name || '').trim();
        const description = String(port.description || '').trim();
        if (!name) {
          return `${label} port name is required.`;
        }
        if (!description) {
          return `${label} port description is required.`;
        }
        if (names.has(name)) {
          return `${label} port names must be unique.`;
        }
        names.add(name);
      }
    }
    return '';
  }

  showNodeEditorError(message) {
    const element = this.byId('agentsV2NodeEditorError');
    if (!element) {
      return;
    }
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  applyConnectionTargetClasses() {
    const sourceOutputPortId = this.connectionDrag?.sourceOutputPortId;
    this.byId('agentsV2WorkflowNodes').querySelectorAll('[data-node-input-port]').forEach((element) => {
      const valid = this.connectionDrag?.taskInput
        ? this.canConnectTaskInput(element.dataset.nodeInputPort)
        : sourceOutputPortId && this.canConnect(sourceOutputPortId, element.dataset.nodeInputPort);
      element.classList.toggle('valid-target', Boolean(valid));
      element.classList.toggle('invalid-target', !valid);
    });
  }

  clearConnectionTargetClasses() {
    this.byId('agentsV2WorkflowNodes')?.querySelectorAll('[data-node-input-port]').forEach((element) => {
      element.classList.remove('valid-target', 'invalid-target', 'hover-target');
    });
  }

  updateHoveredInput(event) {
    const targetInputPortId = this.inputPortIdFromEvent(event);
    this.byId('agentsV2WorkflowNodes').querySelectorAll('[data-node-input-port]').forEach((element) => {
      element.classList.toggle('hover-target', Boolean(targetInputPortId && element.dataset.nodeInputPort === targetInputPortId));
    });
  }

  updatePreviewPath() {
    const preview = this.byId('agentsV2WorkflowEdges').querySelector('.workflow-edge-preview');
    if (preview && this.connectionDrag) {
      preview.setAttribute('d', this.pathD(this.connectionDrag.start, this.connectionDrag.current));
    }
  }

  inputPortIdFromEvent(event) {
    const direct = event.target?.closest?.('[data-node-input-port]');
    if (direct) {
      return direct.dataset.nodeInputPort;
    }
    const fromPoint = this.document.elementsFromPoint?.(event.clientX, event.clientY)
      .find((element) => element.closest?.('[data-node-input-port]'))
      ?.closest('[data-node-input-port]');
    return fromPoint?.dataset.nodeInputPort || null;
  }

  connectorPoint(portId, kind) {
    const port = this.portById(portId, kind === 'output' ? 'outputs' : 'inputs');
    const node = port?.node;
    return {
      x: Number(node?.position?.x || 0) + (kind === 'output' ? NODE_WIDTH : 0),
      y: Number(node?.position?.y || 0) + NODE_MID_Y + (Number(port?.port?.order || 0) * NODE_PORT_ROW_HEIGHT)
    };
  }

  taskInputConnectorPoint() {
    return {
      x: TASK_INPUT_X + TASK_INPUT_WIDTH,
      y: TASK_INPUT_Y + (TASK_INPUT_HEIGHT / 2)
    };
  }

  canvasPoint(event) {
    const canvas = this.byId('agentsV2WorkflowCanvas');
    const rect = canvas.getBoundingClientRect();
    return {
      x: ((event.clientX - rect.left) - this.viewport.x) / this.viewport.scale,
      y: ((event.clientY - rect.top) - this.viewport.y) / this.viewport.scale
    };
  }

  pathD(start, end) {
    const mid = Math.max(40, Math.abs(end.x - start.x) / 2);
    return `M ${start.x} ${start.y} C ${start.x + mid} ${start.y}, ${end.x - mid} ${end.y}, ${end.x} ${end.y}`;
  }

  syncCanvasBounds() {
    const canvas = this.byId('agentsV2WorkflowCanvas');
    const svg = this.byId('agentsV2WorkflowEdges');
    const nodesLayer = this.byId('agentsV2WorkflowNodes');
    if (!canvas || !svg || !nodesLayer) {
      return;
    }
    let width = Math.max(MIN_CANVAS_WIDTH, canvas.clientWidth || 0);
    let height = Math.max(MIN_CANVAS_HEIGHT, canvas.clientHeight || 0);
    for (const node of this.workflow?.nodes || []) {
      const portRows = Math.max(this.nodePorts(node.inputs).length, this.nodePorts(node.outputs).length, 1);
      const nodeHeight = Math.max(NODE_MIN_HEIGHT, portRows * NODE_PORT_ROW_HEIGHT + NODE_HEIGHT_PADDING);
      width = Math.max(width, Number(node.position?.x || 0) + NODE_WIDTH + NODE_PORT_LABEL_EXTENT + CANVAS_PADDING);
      height = Math.max(height, Number(node.position?.y || 0) + nodeHeight + CANVAS_PADDING);
    }
    width = Math.max(width, TASK_INPUT_X + TASK_INPUT_WIDTH + CANVAS_PADDING);
    height = Math.max(height, TASK_INPUT_Y + TASK_INPUT_HEIGHT + CANVAS_PADDING);
    const widthValue = `${Math.ceil(width)}px`;
    const heightValue = `${Math.ceil(height)}px`;
    svg.style.width = widthValue;
    svg.style.height = heightValue;
    svg.setAttribute('width', String(Math.ceil(width)));
    svg.setAttribute('height', String(Math.ceil(height)));
    nodesLayer.style.width = widthValue;
    nodesLayer.style.height = heightValue;
    this.applyViewportTransform();
  }

  applyViewportTransform() {
    const transform = `translate(${this.viewport.x}px, ${this.viewport.y}px) scale(${this.viewport.scale})`;
    for (const element of [this.byId('agentsV2WorkflowEdges'), this.byId('agentsV2WorkflowNodes')]) {
      if (!element) {
        continue;
      }
      element.style.transform = transform;
      element.style.transformOrigin = '0 0';
    }
  }

  async save() {
    if (!this.workflow || this.saving) {
      return;
    }
    this.saving = true;
    this.byId('agentsV2WorkflowSave').disabled = true;
    this.showError('');
    const request = {
      name: this.workflow.name,
      nodes: this.workflow.nodes.map((node) => ({
        id: node.id,
        targetId: node.targetId,
        inputMode: this.nodeInputMode(node),
        inputs: this.reindexPorts(node.inputs).map((port) => this.normalizedPort(port)),
        outputs: this.reindexPorts(node.outputs).map((port) => this.normalizedPort(port)),
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      })),
      connections: this.workflowConnections().map((connection) => ({
        id: connection.id,
        sourceOutputPortId: connection.sourceOutputPortId,
        targetInputPortId: connection.targetInputPortId
      })),
      taskInputPortId: this.portById(this.workflow.taskInputPortId, 'inputs') ? this.workflow.taskInputPortId : null
    };
    if (request.nodes.length > 0 && !request.taskInputPortId) {
      this.showError('Task Input is required before saving this workflow.');
      this.saving = false;
      this.byId('agentsV2WorkflowSave').disabled = false;
      return;
    }
    try {
      const saved = await this.api.updateWorkflow(this.workflow.id, request);
      this.workflow = this.cloneWorkflow(saved);
      await this.onSaved(saved);
      this.render();
    } catch (error) {
      this.showError(error.message || 'Workflow could not be saved.');
    } finally {
      this.saving = false;
      this.byId('agentsV2WorkflowSave').disabled = false;
    }
  }

  randomUuid() {
    const cryptoRef = this.window.crypto;
    if (cryptoRef?.randomUUID) {
      return cryptoRef.randomUUID();
    }
    if (cryptoRef?.getRandomValues) {
      const bytes = new Uint8Array(16);
      cryptoRef.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0'));
      return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`;
    }
    throw new Error('Node UUID generation unavailable.');
  }

  cloneWorkflow(workflow) {
    return {
      ...workflow,
      nodes: (workflow.nodes || []).map((node) => ({
        id: node.id,
        targetId: node.targetId,
        inputMode: this.nodeInputMode(node),
        inputs: this.reindexPorts(node.inputs).map((port) => this.normalizedPort(port)),
        outputs: this.reindexPorts(node.outputs).map((port) => this.normalizedPort(port)),
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      })),
      connections: (workflow.connections || []).map((connection) => ({
        id: connection.id,
        sourceOutputPortId: connection.sourceOutputPortId,
        targetInputPortId: connection.targetInputPortId
      })),
      taskInputPortId: this.portByIdInNodes(workflow.nodes || [], workflow.taskInputPortId, 'inputs') ? workflow.taskInputPortId : null
    };
  }

  cloneNode(node) {
    return {
      id: node.id,
      targetId: node.targetId,
      inputMode: this.nodeInputMode(node),
      inputs: this.reindexPorts(node.inputs).map((port) => ({ ...port })),
      outputs: this.reindexPorts(node.outputs).map((port) => ({ ...port })),
      position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
    };
  }

  nodeInputMode(node) {
    return this.normalizeInputMode(node?.inputMode);
  }

  normalizeInputMode(inputMode) {
    return inputMode === TASK_AND_DEPENDENCIES_INPUT_MODE ? TASK_AND_DEPENDENCIES_INPUT_MODE : DEFAULT_INPUT_MODE;
  }

  nodePorts(ports) {
    return [...(ports || [])].sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
  }

  workflowConnections() {
    return [...(this.workflow?.connections || [])];
  }

  portById(portId, direction = null) {
    for (const node of this.workflow?.nodes || []) {
      for (const candidateDirection of ['inputs', 'outputs']) {
        if (direction && direction !== candidateDirection) {
          continue;
        }
        const port = this.nodePorts(node[candidateDirection]).find((candidate) => candidate.id === portId);
        if (port) {
          return { node, port, direction: candidateDirection };
        }
      }
    }
    return null;
  }

  isPortConnected(portId) {
    return this.workflowConnections().some((connection) =>
      connection.sourceOutputPortId === portId || connection.targetInputPortId === portId
    ) || this.workflow?.taskInputPortId === portId;
  }

  portByIdInNodes(nodes, portId, direction = null) {
    for (const node of nodes || []) {
      for (const candidateDirection of ['inputs', 'outputs']) {
        if (direction && direction !== candidateDirection) {
          continue;
        }
        const port = this.nodePorts(node[candidateDirection]).find((candidate) => candidate.id === portId);
        if (port) {
          return { node, port, direction: candidateDirection };
        }
      }
    }
    return null;
  }

  reindexPorts(ports) {
    return this.nodePorts(ports).map((port, index) => ({ ...port, order: index }));
  }

  normalizedPort(port) {
    return {
      id: port.id,
      name: String(port.name || '').trim(),
      description: String(port.description || '').trim(),
      order: Number(port.order || 0)
    };
  }

  isNodeEditorPortEditing(direction, portId) {
    return this.nodeEditorEditingPortKey === this.nodeEditorPortKey(direction, portId);
  }

  nodeEditorPortKey(direction, portId) {
    return `${direction}:${portId}`;
  }

  agentById(agentId) {
    return this.agents.find((agent) => agent.id === agentId);
  }

  byNodeId(nodeId) {
    return this.document.querySelector(`[data-node-id="${cssEscape(nodeId)}"]`);
  }

  showError(message) {
    const element = this.byId('agentsV2WorkflowBuilderError');
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  byId(id) {
    return this.document.getElementById(id);
  }

  get draftWorkflow() {
    return this.workflow;
  }
}

function cssEscape(value) {
  if (globalThis.CSS?.escape) {
    return globalThis.CSS.escape(value);
  }
  return String(value).replaceAll('"', '\\"');
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}
