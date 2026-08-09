import { createAgentsV2Api } from './agents-v2-api.js';
import { escapeHtml } from './dom-render-helpers.js';

const DEFAULT_OUTPUT_SCHEMA = { type: 'object', properties: {} };

export class AgentsV2Page {
  constructor(options = {}) {
    this.document = options.document || document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api || createAgentsV2Api(options.http);
    this.state = {
      projects: [],
      agents: [],
      workflows: [],
      selectedProjectId: null,
      agentsProjectId: null,
      workflowsProjectId: null,
      activeTab: 'agents',
      editingAgentId: null,
      openWorkflowId: null,
      draftWorkflow: null,
      connectingFromNodeId: null,
      saving: false
    };
    this.projectLoadSequence = 0;
    this.drag = null;
  }

  mount() {
    this.bind();
    this.renderAgentsEmpty();
    this.renderWorkflowsEmpty();
    this.loadProjects();
  }

  dispose() {
    this.document.removeEventListener('mousemove', this.handleDragMove);
    this.document.removeEventListener('mouseup', this.handleDragEnd);
  }

  bind() {
    this.handleDragMove = (event) => this.onDragMove(event);
    this.handleDragEnd = () => this.onDragEnd();
    this.document.addEventListener('mousemove', this.handleDragMove);
    this.document.addEventListener('mouseup', this.handleDragEnd);
    this.byId('agentsV2CreateProject')?.addEventListener('click', () => this.openProjectModal());
    this.byId('agentsV2ProjectCancel')?.addEventListener('click', () => this.closeDialog('agentsV2ProjectDialog'));
    this.byId('agentsV2ProjectForm')?.addEventListener('submit', (event) => this.submitProject(event));
    this.byId('agentsV2CreateAgent')?.addEventListener('click', () => this.openAgentModal());
    this.byId('agentsV2AgentCancel')?.addEventListener('click', () => this.closeDialog('agentsV2AgentDialog'));
    this.byId('agentsV2AgentForm')?.addEventListener('submit', (event) => this.submitAgent(event));
    this.byId('agentsV2CreateWorkflow')?.addEventListener('click', () => this.openWorkflowModal());
    this.byId('agentsV2WorkflowCancel')?.addEventListener('click', () => this.closeDialog('agentsV2WorkflowDialog'));
    this.byId('agentsV2WorkflowForm')?.addEventListener('submit', (event) => this.submitWorkflow(event));
    this.byId('agentsV2AgentsTab')?.addEventListener('click', () => this.setTab('agents'));
    this.byId('agentsV2WorkflowsTab')?.addEventListener('click', () => this.setTab('workflows'));
    this.byId('agentsV2BuilderBack')?.addEventListener('click', () => this.closeWorkflowBuilder());
    this.byId('agentsV2WorkflowSave')?.addEventListener('click', () => this.saveWorkflow());
  }

  async loadProjects() {
    this.showError('agentsV2ProjectsError', '');
    this.byId('agentsV2ProjectsList').innerHTML = '<div class="muted-state">Loading projects...</div>';
    try {
      this.state.projects = await this.api.listProjects();
      this.renderProjects();
    } catch (error) {
      this.byId('agentsV2ProjectsList').innerHTML = '';
      this.showError('agentsV2ProjectsError', error.message || 'Projects failed to load.');
    }
  }

  async selectProject(projectId) {
    const loadSequence = this.projectLoadSequence + 1;
    this.projectLoadSequence = loadSequence;
    this.state.selectedProjectId = projectId;
    this.state.agents = [];
    this.state.workflows = [];
    this.state.agentsProjectId = null;
    this.state.workflowsProjectId = null;
    this.state.openWorkflowId = null;
    this.state.draftWorkflow = null;
    this.state.connectingFromNodeId = null;
    this.renderProjects();
    this.renderProjectWorkspace();
    this.disableProjectActions(true);
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">Loading workflows...</div>';
    await Promise.all([this.loadAgents(projectId, loadSequence), this.loadWorkflows(projectId, loadSequence)]);
    if (this.isCurrentProjectLoad(projectId, loadSequence)) {
      this.disableProjectActions(false);
    }
  }

  async loadAgents(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence) {
    if (!projectId) {
      this.renderAgentsEmpty();
      return;
    }
    this.showError('agentsV2AgentsError', '');
    try {
      const agents = await this.api.listProjectAgents(projectId);
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.agents = agents;
      this.state.agentsProjectId = projectId;
      this.renderAgents();
      this.renderWorkflowBuilder();
    } catch (error) {
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.agents = [];
      this.state.agentsProjectId = null;
      this.byId('agentsV2AgentsList').innerHTML = '';
      this.showError('agentsV2AgentsError', error.message || 'Agents failed to load.');
    }
  }

  async loadWorkflows(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence) {
    if (!projectId) {
      this.renderWorkflowsEmpty();
      return;
    }
    this.showError('agentsV2WorkflowsError', '');
    try {
      const workflows = await this.api.listProjectWorkflows(projectId);
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.workflows = workflows;
      this.state.workflowsProjectId = projectId;
      this.renderWorkflows();
    } catch (error) {
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.workflows = [];
      this.state.workflowsProjectId = null;
      this.byId('agentsV2WorkflowsList').innerHTML = '';
      this.showError('agentsV2WorkflowsError', error.message || 'Workflows failed to load.');
    }
  }

  renderProjects() {
    const list = this.byId('agentsV2ProjectsList');
    if (!this.state.projects.length) {
      list.innerHTML = '<div class="muted-state">No projects yet.</div>';
      return;
    }
    list.innerHTML = this.state.projects.map((project) => `
      <button class="agents-v2-project-row ${project.id === this.state.selectedProjectId ? 'selected' : ''}" type="button" data-project-id="${escapeHtml(project.id)}">
        <span></span>
        <strong>${escapeHtml(project.name)}</strong>
      </button>
    `).join('');
    list.querySelectorAll('[data-project-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectProject(element.dataset.projectId));
    });
  }

  renderProjectWorkspace() {
    const project = this.currentProject();
    this.byId('agentsV2ProjectTitle').textContent = project ? project.name : 'Select a project';
    this.byId('agentsV2ProjectCrumbs').textContent = project ? `Projects / ${project.name}` : 'Projects';
    this.setTab(this.state.activeTab);
  }

  setTab(tab) {
    this.state.activeTab = tab;
    this.byId('agentsV2AgentsTab')?.classList.toggle('active', tab === 'agents');
    this.byId('agentsV2WorkflowsTab')?.classList.toggle('active', tab === 'workflows');
    this.byId('agentsV2AgentsPane')?.classList.toggle('hidden', tab !== 'agents');
    this.byId('agentsV2WorkflowsPane')?.classList.toggle('hidden', tab !== 'workflows');
  }

  renderAgentsEmpty() {
    this.byId('agentsV2CreateAgent').disabled = true;
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">No project selected.</div>';
  }

  renderAgents() {
    const list = this.byId('agentsV2AgentsList');
    if (!this.state.agents.length) {
      list.innerHTML = '<div class="muted-state">No agents yet.</div>';
      return;
    }
    list.innerHTML = this.state.agents.map((agent) => `
      <article class="agents-v2-card">
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(this.agentPreview(agent))}</p>
        <button class="button small secondary" type="button" data-agent-id="${escapeHtml(agent.id)}">Edit</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.openAgentModal(element.dataset.agentId));
    });
  }

  renderWorkflowsEmpty() {
    this.byId('agentsV2CreateWorkflow').disabled = true;
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">No project selected.</div>';
  }

  renderWorkflows() {
    const list = this.byId('agentsV2WorkflowsList');
    if (!this.state.workflows.length) {
      list.innerHTML = '<div class="muted-state">No workflows yet.</div>';
      return;
    }
    list.innerHTML = this.state.workflows.map((workflow) => `
      <article class="agents-v2-card">
        <h3>${escapeHtml(workflow.name)}</h3>
        <p>${(workflow.nodes || []).length} nodes</p>
        <button class="button small secondary" type="button" data-workflow-id="${escapeHtml(workflow.id)}">Open</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-workflow-id]').forEach((element) => {
      element.addEventListener('click', () => this.openWorkflowBuilder(element.dataset.workflowId));
    });
  }

  openProjectModal() {
    this.showError('agentsV2ProjectModalError', '');
    this.byId('agentsV2ProjectName').value = '';
    this.openDialog('agentsV2ProjectDialog');
  }

  async submitProject(event) {
    event.preventDefault();
    if (this.state.saving) {
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2ProjectSave').disabled = true;
    try {
      const project = await this.api.createProject({ name: this.byId('agentsV2ProjectName').value });
      this.closeDialog('agentsV2ProjectDialog');
      await this.loadProjects();
      await this.selectProject(project.id);
    } catch (error) {
      this.showError('agentsV2ProjectModalError', error.message || 'Project could not be saved.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2ProjectSave').disabled = false;
    }
  }

  async openAgentModal(agentId = null) {
    if (!this.projectDataCurrent()) {
      return;
    }
    this.state.editingAgentId = agentId;
    this.showError('agentsV2AgentModalError', '');
    this.showFieldError('');
    this.byId('agentsV2AgentModalTitle').textContent = agentId ? 'Edit Agent' : 'Create Agent';
    this.byId('agentsV2AgentName').value = '';
    this.byId('agentsV2AgentInstructions').value = '';
    this.byId('agentsV2AgentOutputJson').value = JSON.stringify(DEFAULT_OUTPUT_SCHEMA, null, 2);
    if (agentId) {
      try {
        const agent = await this.api.getAgent(agentId);
        if (agent.projectId !== this.state.selectedProjectId) {
          this.showError('agentsV2AgentsError', 'Agent details do not belong to the selected project.');
          return;
        }
        this.byId('agentsV2AgentName').value = agent.name || '';
        this.byId('agentsV2AgentInstructions').value = agent.instructions || '';
        this.byId('agentsV2AgentOutputJson').value = JSON.stringify(agent.outputSchema || DEFAULT_OUTPUT_SCHEMA, null, 2);
      } catch (error) {
        this.showError('agentsV2AgentsError', error.message || 'Agent details failed to load.');
        return;
      }
    }
    this.openDialog('agentsV2AgentDialog');
  }

  async submitAgent(event) {
    event.preventDefault();
    if (this.state.saving) {
      return;
    }
    const outputSchema = this.parseOutputSchema();
    if (!outputSchema) {
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2AgentSave').disabled = true;
    this.showError('agentsV2AgentModalError', '');
    const request = {
      name: this.byId('agentsV2AgentName').value,
      instructions: this.byId('agentsV2AgentInstructions').value,
      outputSchema
    };
    try {
      if (this.state.editingAgentId) {
        await this.api.updateAgent(this.state.editingAgentId, request);
      } else {
        await this.api.createAgent(this.state.selectedProjectId, request);
      }
      this.closeDialog('agentsV2AgentDialog');
      await this.loadAgents();
    } catch (error) {
      this.showError('agentsV2AgentModalError', error.message || 'Agent could not be saved.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2AgentSave').disabled = false;
    }
  }

  openWorkflowModal() {
    if (!this.projectDataCurrent()) {
      return;
    }
    this.showError('agentsV2WorkflowModalError', '');
    this.byId('agentsV2WorkflowName').value = '';
    this.openDialog('agentsV2WorkflowDialog');
  }

  async submitWorkflow(event) {
    event.preventDefault();
    if (this.state.saving || !this.projectDataCurrent()) {
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2WorkflowCreateSave').disabled = true;
    try {
      const workflow = await this.api.createWorkflow(this.state.selectedProjectId, { name: this.byId('agentsV2WorkflowName').value });
      this.closeDialog('agentsV2WorkflowDialog');
      await this.loadWorkflows();
      await this.openWorkflowBuilder(workflow.id);
    } catch (error) {
      this.showError('agentsV2WorkflowModalError', error.message || 'Workflow could not be saved.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2WorkflowCreateSave').disabled = false;
    }
  }

  async openWorkflowBuilder(workflowId) {
    if (!this.projectDataCurrent()) {
      return;
    }
    this.showError('agentsV2WorkflowBuilderError', '');
    try {
      const workflow = await this.api.getWorkflow(workflowId);
      if (workflow.projectId !== this.state.selectedProjectId) {
        this.showError('agentsV2WorkflowsError', 'Workflow does not belong to the selected project.');
        return;
      }
      this.state.openWorkflowId = workflow.id;
      this.state.draftWorkflow = this.cloneWorkflow(workflow);
      this.state.connectingFromNodeId = null;
      this.byId('agentsV2BuilderTitle').textContent = workflow.name;
      this.byId('agentsV2BuilderCrumbs').textContent = `Projects / ${this.currentProject()?.name || ''} / Workflows / ${workflow.name}`;
      this.byId('agentsV2Workspace').classList.add('hidden');
      this.byId('agentsV2Builder').classList.remove('hidden');
      this.renderWorkflowBuilder();
    } catch (error) {
      this.showError('agentsV2WorkflowsError', error.message || 'Workflow failed to load.');
    }
  }

  closeWorkflowBuilder() {
    this.state.openWorkflowId = null;
    this.state.draftWorkflow = null;
    this.state.connectingFromNodeId = null;
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
  }

  addNode(agentId) {
    if (!this.state.draftWorkflow) {
      return;
    }
    const index = this.state.draftWorkflow.nodes.length;
    this.state.draftWorkflow.nodes.push({
      id: this.randomUuid(),
      targetId: agentId,
      dependsOnNodeIds: [],
      position: { x: 120 + (index % 3) * 220, y: 90 + Math.floor(index / 3) * 150 }
    });
    this.renderWorkflowBuilder();
  }

  removeNode(nodeId) {
    if (!this.state.draftWorkflow) {
      return;
    }
    this.state.draftWorkflow.nodes = this.state.draftWorkflow.nodes
      .filter((node) => node.id !== nodeId)
      .map((node) => ({ ...node, dependsOnNodeIds: (node.dependsOnNodeIds || []).filter((id) => id !== nodeId) }));
    if (this.state.connectingFromNodeId === nodeId) {
      this.state.connectingFromNodeId = null;
    }
    this.renderWorkflowBuilder();
  }

  startConnection(nodeId) {
    this.state.connectingFromNodeId = nodeId;
    this.renderWorkflowBuilder();
  }

  connectTo(targetNodeId) {
    const sourceNodeId = this.state.connectingFromNodeId;
    if (!sourceNodeId || sourceNodeId === targetNodeId || !this.state.draftWorkflow) {
      return;
    }
    const target = this.state.draftWorkflow.nodes.find((node) => node.id === targetNodeId);
    const exists = this.state.draftWorkflow.nodes.some((node) => node.id === sourceNodeId);
    if (!target || !exists || (target.dependsOnNodeIds || []).includes(sourceNodeId)) {
      return;
    }
    target.dependsOnNodeIds = [...(target.dependsOnNodeIds || []), sourceNodeId];
    this.state.connectingFromNodeId = null;
    this.renderWorkflowBuilder();
  }

  removeConnection(sourceNodeId, targetNodeId) {
    const target = this.state.draftWorkflow?.nodes.find((node) => node.id === targetNodeId);
    if (!target) {
      return;
    }
    target.dependsOnNodeIds = (target.dependsOnNodeIds || []).filter((id) => id !== sourceNodeId);
    this.renderWorkflowBuilder();
  }

  renderWorkflowBuilder() {
    if (!this.state.draftWorkflow) {
      return;
    }
    const palette = this.byId('agentsV2WorkflowAgentList');
    palette.innerHTML = this.state.agents.map((agent) => `
      <button class="agents-v2-agent-palette-row" type="button" data-add-agent-id="${escapeHtml(agent.id)}">
        ${escapeHtml(agent.name)}
      </button>
    `).join('') || '<div class="muted-state">No agents yet.</div>';
    palette.querySelectorAll('[data-add-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.addNode(element.dataset.addAgentId));
    });

    const nodesLayer = this.byId('agentsV2WorkflowNodes');
    const nodes = this.state.draftWorkflow.nodes || [];
    nodesLayer.innerHTML = nodes.map((node) => this.renderNode(node)).join('');
    nodesLayer.querySelectorAll('[data-node-id]').forEach((element) => {
      element.addEventListener('mousedown', (event) => this.onNodeMouseDown(event, element.dataset.nodeId));
    });
    nodesLayer.querySelectorAll('[data-node-remove]').forEach((element) => {
      element.addEventListener('click', () => this.removeNode(element.dataset.nodeRemove));
    });
    nodesLayer.querySelectorAll('[data-connect-from]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.startConnection(element.dataset.connectFrom);
      });
    });
    nodesLayer.querySelectorAll('[data-connect-to]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.connectTo(element.dataset.connectTo);
      });
    });
    nodesLayer.querySelectorAll('[data-remove-connection]').forEach((element) => {
      element.addEventListener('click', (event) => {
        event.stopPropagation();
        this.removeConnection(element.dataset.sourceNodeId, element.dataset.targetNodeId);
      });
    });
    this.renderEdges();
  }

  renderNode(node) {
    const agent = this.agentById(node.targetId);
    const deps = (node.dependsOnNodeIds || []).map((sourceId) => {
      const source = this.state.draftWorkflow.nodes.find((candidate) => candidate.id === sourceId);
      return `
        <button class="node-connection-chip" type="button" data-remove-connection="${escapeHtml(sourceId)}" data-source-node-id="${escapeHtml(sourceId)}" data-target-node-id="${escapeHtml(node.id)}">
          ${escapeHtml(this.agentById(source?.targetId)?.name || 'Node')} ×
        </button>
      `;
    }).join('');
    return `
      <article class="workflow-node ${this.state.connectingFromNodeId === node.id ? 'connecting' : ''}" data-node-id="${escapeHtml(node.id)}" style="left:${Number(node.position?.x || 0)}px; top:${Number(node.position?.y || 0)}px;">
        <div class="workflow-node-title">
          <button class="node-handle input" type="button" title="Connect to this node" data-connect-to="${escapeHtml(node.id)}">↓</button>
          <strong>${escapeHtml(agent?.name || 'Unknown agent')}</strong>
          <button class="node-delete" type="button" title="Remove node" data-node-remove="${escapeHtml(node.id)}">×</button>
        </div>
        <div class="workflow-node-connections">${deps}</div>
        <button class="node-handle output" type="button" title="Connect from this node" data-connect-from="${escapeHtml(node.id)}">→</button>
      </article>
    `;
  }

  renderEdges() {
    const svg = this.byId('agentsV2WorkflowEdges');
    const nodes = this.state.draftWorkflow.nodes || [];
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const lines = [];
    for (const target of nodes) {
      for (const sourceId of target.dependsOnNodeIds || []) {
        const source = byId.get(sourceId);
        if (!source) {
          continue;
        }
        const x1 = Number(source.position?.x || 0) + 180;
        const y1 = Number(source.position?.y || 0) + 52;
        const x2 = Number(target.position?.x || 0);
        const y2 = Number(target.position?.y || 0) + 52;
        const mid = Math.max(40, Math.abs(x2 - x1) / 2);
        lines.push(`<path d="M ${x1} ${y1} C ${x1 + mid} ${y1}, ${x2 - mid} ${y2}, ${x2} ${y2}" marker-end="url(#agentsV2Arrow)" />`);
      }
    }
    svg.innerHTML = `
      <defs>
        <marker id="agentsV2Arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z"></path>
        </marker>
      </defs>
      ${lines.join('')}
    `;
  }

  onNodeMouseDown(event, nodeId) {
    if (event.target.closest('button')) {
      return;
    }
    const node = this.state.draftWorkflow?.nodes.find((candidate) => candidate.id === nodeId);
    if (!node) {
      return;
    }
    this.drag = {
      nodeId,
      startX: event.clientX,
      startY: event.clientY,
      originalX: Number(node.position?.x || 0),
      originalY: Number(node.position?.y || 0)
    };
  }

  onDragMove(event) {
    if (!this.drag || !this.state.draftWorkflow) {
      return;
    }
    const node = this.state.draftWorkflow.nodes.find((candidate) => candidate.id === this.drag.nodeId);
    if (!node) {
      return;
    }
    node.position = {
      x: Math.max(0, this.drag.originalX + event.clientX - this.drag.startX),
      y: Math.max(0, this.drag.originalY + event.clientY - this.drag.startY)
    };
    this.renderWorkflowBuilder();
  }

  onDragEnd() {
    this.drag = null;
  }

  async saveWorkflow() {
    if (!this.state.draftWorkflow || this.state.saving) {
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2WorkflowSave').disabled = true;
    this.showError('agentsV2WorkflowBuilderError', '');
    const request = {
      name: this.state.draftWorkflow.name,
      nodes: this.state.draftWorkflow.nodes.map((node) => ({
        id: node.id,
        targetId: node.targetId,
        dependsOnNodeIds: [...(node.dependsOnNodeIds || [])],
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      }))
    };
    try {
      const saved = await this.api.updateWorkflow(this.state.draftWorkflow.id, request);
      this.state.draftWorkflow = this.cloneWorkflow(saved);
      await this.loadWorkflows();
      this.renderWorkflowBuilder();
    } catch (error) {
      this.showError('agentsV2WorkflowBuilderError', error.message || 'Workflow could not be saved.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2WorkflowSave').disabled = false;
    }
  }

  parseOutputSchema() {
    this.showFieldError('');
    try {
      const parsed = JSON.parse(this.byId('agentsV2AgentOutputJson').value);
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        this.showFieldError('Output JSON must be a JSON object.');
        return null;
      }
      return parsed;
    } catch (_) {
      this.showFieldError('Output JSON is not valid JSON.');
      return null;
    }
  }

  cloneWorkflow(workflow) {
    return {
      ...workflow,
      nodes: (workflow.nodes || []).map((node) => ({
        id: node.id,
        targetId: node.targetId,
        dependsOnNodeIds: [...(node.dependsOnNodeIds || [])],
        position: { x: Number(node.position?.x || 0), y: Number(node.position?.y || 0) }
      }))
    };
  }

  disableProjectActions(disabled) {
    this.byId('agentsV2CreateAgent').disabled = disabled || !this.projectDataCurrent();
    this.byId('agentsV2CreateWorkflow').disabled = disabled || !this.projectDataCurrent();
  }

  projectDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.agentsProjectId === this.state.selectedProjectId
      && this.state.workflowsProjectId === this.state.selectedProjectId;
  }

  isCurrentProjectLoad(projectId, loadSequence) {
    return this.state.selectedProjectId === projectId && this.projectLoadSequence === loadSequence;
  }

  currentProject() {
    return this.state.projects.find((project) => project.id === this.state.selectedProjectId);
  }

  agentById(agentId) {
    return this.state.agents.find((agent) => agent.id === agentId);
  }

  agentPreview(agent) {
    return agent.instructions || 'Reusable agent definition';
  }

  randomUuid() {
    return this.window.crypto?.randomUUID ? this.window.crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
  }

  openDialog(id) {
    const dialog = this.byId(id);
    if (dialog.showModal) {
      dialog.showModal();
    } else {
      dialog.setAttribute('open', 'open');
    }
  }

  closeDialog(id) {
    const dialog = this.byId(id);
    if (dialog.close) {
      dialog.close();
    } else {
      dialog.removeAttribute('open');
    }
  }

  showError(id, message) {
    const element = this.byId(id);
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  showFieldError(message) {
    const element = this.byId('agentsV2AgentJsonError');
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  byId(id) {
    return this.document.getElementById(id);
  }

  testApi() {
    return {
      loadProjects: () => this.loadProjects(),
      selectProject: (projectId) => this.selectProject(projectId),
      openAgentModal: (agentId) => this.openAgentModal(agentId),
      openWorkflowBuilder: (workflowId) => this.openWorkflowBuilder(workflowId),
      addNode: (agentId) => this.addNode(agentId),
      connectTo: (nodeId) => this.connectTo(nodeId),
      startConnection: (nodeId) => this.startConnection(nodeId),
      removeConnection: (sourceNodeId, targetNodeId) => this.removeConnection(sourceNodeId, targetNodeId),
      removeNode: (nodeId) => this.removeNode(nodeId),
      saveWorkflow: () => this.saveWorkflow(),
      parseOutputSchema: () => this.parseOutputSchema(),
      state: this.state
    };
  }
}
