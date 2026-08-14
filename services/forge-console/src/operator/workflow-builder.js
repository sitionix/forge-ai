import { escapeHtml } from './dom-render-helpers.js';
const NODE_WIDTH = 204;
const NODE_MID_Y = 52;
const DEFAULT_INPUT_MODE = 'DEPENDENCIES_ONLY';
const TASK_AND_DEPENDENCIES_INPUT_MODE = 'TASK_AND_DEPENDENCIES';
const NODE_DRAG_THRESHOLD = 3;

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
    this.connectionDrag = null;
    this.nodeEditorNodeId = null;
    this.nodeEditorDraft = null;
    this.nodeEditorEditingPorts = new Set();
    this.saving = false;
  }

  bind() {
    this.handlePointerMove = (event) => this.onPointerMove(event);
    this.handlePointerUp = (event) => this.onPointerUp(event);
    this.handlePointerCancel = () => this.cancelConnectionDrag();
    this.handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        this.cancelConnectionDrag();
      }
    };
    this.document.addEventListener('pointermove', this.handlePointerMove);
    this.document.addEventListener('pointerup', this.handlePointerUp);
    this.document.addEventListener('pointercancel', this.handlePointerCancel);
    this.document.addEventListener('keydown', this.handleKeyDown);
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
  }

  open(workflow, project, agents) {
    this.workflow = this.cloneWorkflow(workflow);
    this.project = project;
    this.agents = agents || [];
    this.nodeDrag = null;
    this.connectionDrag = null;
    this.nodeEditorNodeId = null;
    this.nodeEditorDraft = null;
    this.nodeEditorEditingPorts.clear();
    this.showError('');
    this.byId('agentsV2BuilderTitle').textContent = workflow.name;
    this.byId('agentsV2BuilderCrumbs').textContent = `Projects / ${project?.name || ''} / Workflows / ${workflow.name}`;
    this.render();
  }

  close() {
    this.workflow = null;
    this.project = null;
    this.nodeDrag = null;
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
    try {
      nodeId = this.randomUuid();
    } catch (error) {
      this.showError(error.message || 'Node UUID generation unavailable.');
      return;
    }
    if (this.workflow.nodes.some((node) => node.id === nodeId)) {
      this.showError('Node UUID generation produced a duplicate ID.');
      return;
    }
    const index = this.workflow.nodes.length;
    this.workflow.nodes.push({
      id: nodeId,
      targetId: agentId,
      dependsOnNodeIds: [],
      inputMode: DEFAULT_INPUT_MODE,
      inputs: [],
      outputs: [],
      position: { x: 120 + (index % 3) * 240, y: 90 + Math.floor(index / 3) * 160 }
    });
    this.render();
  }

  removeNode(nodeId) {
    if (!this.workflow) {
      return;
    }
    this.workflow.nodes = this.workflow.nodes
      .filter((node) => node.id !== nodeId)
      .map((node) => ({ ...node, dependsOnNodeIds: (node.dependsOnNodeIds || []).filter((id) => id !== nodeId) }));
    if (this.connectionDrag?.sourceNodeId === nodeId) {
      this.cancelConnectionDrag();
    }
    this.render();
  }

  removeConnection(sourceNodeId, targetNodeId) {
    const target = this.workflow?.nodes.find((node) => node.id === targetNodeId);
    if (!target) {
      return;
    }
    target.dependsOnNodeIds = (target.dependsOnNodeIds || []).filter((id) => id !== sourceNodeId);
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
    nodesLayer.querySelectorAll('[data-node-id]').forEach((element) => {
      element.addEventListener('pointerdown', (event) => this.onNodePointerDown(event, element.dataset.nodeId));
    });
    nodesLayer.querySelectorAll('[data-node-remove]').forEach((element) => {
      element.addEventListener('click', () => this.removeNode(element.dataset.nodeRemove));
    });
    nodesLayer.querySelectorAll('[data-node-output]').forEach((element) => {
      element.addEventListener('pointerdown', (event) => this.startConnectionDrag(event, element.dataset.nodeOutput));
    });
  }

  renderNode(node) {
    const agent = this.agentById(node.targetId);
    const inputs = this.nodePorts(node.inputs);
    const outputs = this.nodePorts(node.outputs);
    return `
      <article class="workflow-node" data-node-id="${escapeHtml(node.id)}" style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px;">
        <button class="node-handle input" type="button" title="Input" data-node-input="${escapeHtml(node.id)}" aria-label="Input for ${escapeHtml(agent?.name || 'node')}"></button>
        <div class="workflow-node-port-list input" aria-label="Configured inputs">
          ${this.renderCompactPorts(inputs)}
        </div>
        <div class="workflow-node-content">
          <strong>${escapeHtml(agent?.name || 'Unknown agent')}</strong>
          <span>${escapeHtml(agent?.instructions || 'Reusable agent')}</span>
        </div>
        <button class="node-delete" type="button" title="Remove node" data-node-remove="${escapeHtml(node.id)}" aria-label="Remove node">×</button>
        <div class="workflow-node-port-list output" aria-label="Configured outputs">
          ${this.renderCompactPorts(outputs)}
        </div>
        <button class="node-handle output" type="button" title="Output" data-node-output="${escapeHtml(node.id)}" aria-label="Output from ${escapeHtml(agent?.name || 'node')}"></button>
      </article>
    `;
  }

  renderCompactPorts(ports) {
    return ports.map((port) => `
      <div class="workflow-node-port" title="${escapeHtml(port.name)}">
        <span>${escapeHtml(port.name)}</span>
      </div>
    `).join('');
  }

  renderEdges() {
    const svg = this.byId('agentsV2WorkflowEdges');
    const nodes = this.workflow?.nodes || [];
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const groups = [];
    for (const target of nodes) {
      for (const sourceId of target.dependsOnNodeIds || []) {
        const source = byId.get(sourceId);
        if (!source) {
          continue;
        }
        const start = this.connectorPoint(source.id, 'output');
        const end = this.connectorPoint(target.id, 'input');
        const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
        groups.push(`
          <g class="workflow-edge" data-edge-source="${escapeHtml(source.id)}" data-edge-target="${escapeHtml(target.id)}">
            <path class="edge-visible" d="${this.pathD(start, end)}" marker-end="url(#agentsV2Arrow)" />
            <path class="edge-hit" d="${this.pathD(start, end)}" />
            <circle class="edge-remove" data-remove-connection data-source-node-id="${escapeHtml(source.id)}" data-target-node-id="${escapeHtml(target.id)}" cx="${midpoint.x}" cy="${midpoint.y}" r="10" />
            <text class="edge-remove-label" data-remove-connection data-source-node-id="${escapeHtml(source.id)}" data-target-node-id="${escapeHtml(target.id)}" x="${midpoint.x}" y="${midpoint.y + 4}">×</text>
          </g>
        `);
      }
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
        this.removeConnection(element.dataset.sourceNodeId, element.dataset.targetNodeId);
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

  startConnectionDrag(event, sourceNodeId) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.workflow?.nodes.some((node) => node.id === sourceNodeId)) {
      return;
    }
    const start = this.connectorPoint(sourceNodeId, 'output');
    this.connectionDrag = { sourceNodeId, start, current: start };
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
    if (!this.connectionDrag) {
      return;
    }
    const targetNodeId = this.inputNodeIdFromEvent(event);
    if (targetNodeId && this.canConnect(this.connectionDrag.sourceNodeId, targetNodeId)) {
      const target = this.workflow.nodes.find((node) => node.id === targetNodeId);
      target.dependsOnNodeIds = [...(target.dependsOnNodeIds || []), this.connectionDrag.sourceNodeId];
      target.inputMode = this.nodeInputMode(target);
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
      x: Math.max(0, this.nodeDrag.originalX + deltaX),
      y: Math.max(0, this.nodeDrag.originalY + deltaY)
    };
    const element = this.byNodeId(node.id);
    if (element) {
      element.style.left = `${node.position.x}px`;
      element.style.top = `${node.position.y}px`;
    }
    this.updateConnectedEdges(node.id);
  }

  updateConnectedEdges(nodeId) {
    const svg = this.byId('agentsV2WorkflowEdges');
    svg.querySelectorAll(`[data-edge-source="${cssEscape(nodeId)}"], [data-edge-target="${cssEscape(nodeId)}"]`).forEach((group) => {
      this.updateEdgeGroup(group);
    });
  }

  updateEdgeGroup(group) {
    const source = this.workflow?.nodes.find((node) => node.id === group.dataset.edgeSource);
    const target = this.workflow?.nodes.find((node) => node.id === group.dataset.edgeTarget);
    if (!source || !target) {
      return;
    }
    const start = this.connectorPoint(source.id, 'output');
    const end = this.connectorPoint(target.id, 'input');
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

  canConnect(sourceNodeId, targetNodeId) {
    if (!this.workflow || sourceNodeId === targetNodeId) {
      return false;
    }
    const sourceExists = this.workflow.nodes.some((node) => node.id === sourceNodeId);
    const target = this.workflow.nodes.find((node) => node.id === targetNodeId);
    return Boolean(sourceExists && target && !(target.dependsOnNodeIds || []).includes(sourceNodeId));
  }

  openNodeEditor(nodeId) {
    const node = this.workflow?.nodes.find((candidate) => candidate.id === nodeId);
    if (!node) {
      return;
    }
    this.nodeEditorNodeId = nodeId;
    this.nodeEditorDraft = this.cloneNode(node);
    this.nodeEditorEditingPorts.clear();
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
    this.nodeEditorEditingPorts.clear();
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
    const hasDependencies = Boolean((node.dependsOnNodeIds || []).length);
    if (!hasDependencies) {
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
    this.nodeEditorEditingPorts.add(this.nodeEditorPortKey(direction, id));
    this.renderNodeEditor();
  }

  removeNodeEditorPort(direction, portId) {
    if (!this.nodeEditorDraft || !['inputs', 'outputs'].includes(direction)) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    this.nodeEditorDraft[direction] = this.reindexPorts(
      this.nodePorts(this.nodeEditorDraft[direction]).filter((port) => port.id !== portId)
    );
    this.nodeEditorEditingPorts.delete(this.nodeEditorPortKey(direction, portId));
    this.renderNodeEditor();
  }

  editNodeEditorPort(direction, portId) {
    if (!this.nodeEditorDraft || !['inputs', 'outputs'].includes(direction)) {
      return;
    }
    this.syncNodeEditorDraftFromDom();
    this.nodeEditorEditingPorts.add(this.nodeEditorPortKey(direction, portId));
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
    const sourceNodeId = this.connectionDrag?.sourceNodeId;
    this.byId('agentsV2WorkflowNodes').querySelectorAll('[data-node-input]').forEach((element) => {
      const valid = sourceNodeId && this.canConnect(sourceNodeId, element.dataset.nodeInput);
      element.classList.toggle('valid-target', Boolean(valid));
      element.classList.toggle('invalid-target', !valid);
    });
  }

  clearConnectionTargetClasses() {
    this.byId('agentsV2WorkflowNodes')?.querySelectorAll('[data-node-input]').forEach((element) => {
      element.classList.remove('valid-target', 'invalid-target', 'hover-target');
    });
  }

  updateHoveredInput(event) {
    const targetNodeId = this.inputNodeIdFromEvent(event);
    this.byId('agentsV2WorkflowNodes').querySelectorAll('[data-node-input]').forEach((element) => {
      element.classList.toggle('hover-target', Boolean(targetNodeId && element.dataset.nodeInput === targetNodeId));
    });
  }

  updatePreviewPath() {
    const preview = this.byId('agentsV2WorkflowEdges').querySelector('.workflow-edge-preview');
    if (preview && this.connectionDrag) {
      preview.setAttribute('d', this.pathD(this.connectionDrag.start, this.connectionDrag.current));
    }
  }

  inputNodeIdFromEvent(event) {
    const direct = event.target?.closest?.('[data-node-input]');
    if (direct) {
      return direct.dataset.nodeInput;
    }
    const fromPoint = this.document.elementsFromPoint?.(event.clientX, event.clientY)
      .find((element) => element.closest?.('[data-node-input]'))
      ?.closest('[data-node-input]');
    return fromPoint?.dataset.nodeInput || null;
  }

  connectorPoint(nodeId, kind) {
    const handle = this.document.querySelector(`[data-node-${kind}="${cssEscape(nodeId)}"]`);
    const canvas = this.byId('agentsV2WorkflowCanvas');
    const handleRect = handle?.getBoundingClientRect();
    const canvasRect = canvas?.getBoundingClientRect();
    if (handleRect && canvasRect && (handleRect.width || handleRect.height || handleRect.left || handleRect.top)) {
      return {
        x: handleRect.left - canvasRect.left + canvas.scrollLeft + handleRect.width / 2,
        y: handleRect.top - canvasRect.top + canvas.scrollTop + handleRect.height / 2
      };
    }
    const node = this.workflow?.nodes.find((candidate) => candidate.id === nodeId);
    return {
      x: Number(node?.position?.x || 0) + (kind === 'output' ? NODE_WIDTH : 0),
      y: Number(node?.position?.y || 0) + NODE_MID_Y
    };
  }

  canvasPoint(event) {
    const canvas = this.byId('agentsV2WorkflowCanvas');
    const rect = canvas.getBoundingClientRect();
    return {
      x: event.clientX - rect.left + canvas.scrollLeft,
      y: event.clientY - rect.top + canvas.scrollTop
    };
  }

  pathD(start, end) {
    const mid = Math.max(40, Math.abs(end.x - start.x) / 2);
    return `M ${start.x} ${start.y} C ${start.x + mid} ${start.y}, ${end.x - mid} ${end.y}, ${end.x} ${end.y}`;
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
        dependsOnNodeIds: [...(node.dependsOnNodeIds || [])],
        inputMode: this.nodeInputMode(node),
        inputs: this.reindexPorts(node.inputs).map((port) => this.normalizedPort(port)),
        outputs: this.reindexPorts(node.outputs).map((port) => this.normalizedPort(port)),
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      }))
    };
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
        dependsOnNodeIds: [...(node.dependsOnNodeIds || [])],
        inputMode: this.nodeInputMode(node),
        inputs: this.reindexPorts(node.inputs).map((port) => this.normalizedPort(port)),
        outputs: this.reindexPorts(node.outputs).map((port) => this.normalizedPort(port)),
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      }))
    };
  }

  cloneNode(node) {
    return {
      id: node.id,
      targetId: node.targetId,
      dependsOnNodeIds: [...(node.dependsOnNodeIds || [])],
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
    return this.nodeEditorEditingPorts.has(this.nodeEditorPortKey(direction, portId));
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
