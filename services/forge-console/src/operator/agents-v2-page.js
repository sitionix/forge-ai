import { createAgentsV2Api } from './agents-v2-api.js';
import { escapeHtml } from './dom-render-helpers.js';

const DEFAULT_OUTPUT_SCHEMA = {
  type: 'object',
  properties: {}
};

export class AgentsV2Page {
  constructor(options = {}) {
    this.document = options.document || document;
    this.api = options.api || createAgentsV2Api(options.http);
    this.state = {
      projects: [],
      agents: [],
      selectedProjectId: null,
      agentsProjectId: null,
      editingAgentId: null,
      saving: false
    };
    this.agentLoadSequence = 0;
  }

  mount() {
    this.bind();
    this.renderAgentsEmpty();
    this.loadProjects();
  }

  dispose() {
  }

  bind() {
    this.byId('agentsV2CreateProject')?.addEventListener('click', () => this.openProjectModal());
    this.byId('agentsV2ProjectCancel')?.addEventListener('click', () => this.closeDialog('agentsV2ProjectDialog'));
    this.byId('agentsV2ProjectForm')?.addEventListener('submit', (event) => this.submitProject(event));
    this.byId('agentsV2CreateAgent')?.addEventListener('click', () => this.openAgentModal());
    this.byId('agentsV2AgentCancel')?.addEventListener('click', () => this.closeDialog('agentsV2AgentDialog'));
    this.byId('agentsV2AgentForm')?.addEventListener('submit', (event) => this.submitAgent(event));
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
    const loadSequence = this.agentLoadSequence + 1;
    this.agentLoadSequence = loadSequence;
    this.state.selectedProjectId = projectId;
    this.state.agents = [];
    this.state.agentsProjectId = null;
    this.renderProjects();
    this.byId('agentsV2CreateAgent').disabled = true;
    const project = this.state.projects.find((candidate) => candidate.id === projectId);
    this.byId('agentsV2AgentsSubtitle').textContent = project ? project.name : 'Selected project.';
    this.showError('agentsV2AgentsError', '');
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    await this.loadAgents(projectId, loadSequence);
  }

  async loadAgents(projectId = this.state.selectedProjectId, loadSequence = this.agentLoadSequence) {
    if (!projectId) {
      this.renderAgentsEmpty();
      return;
    }
    try {
      const agents = await this.api.listProjectAgents(projectId);
      if (!this.isCurrentAgentLoad(projectId, loadSequence)) {
        return;
      }
      this.state.agents = agents;
      this.state.agentsProjectId = projectId;
      this.renderAgents();
      this.byId('agentsV2CreateAgent').disabled = false;
    } catch (error) {
      if (!this.isCurrentAgentLoad(projectId, loadSequence)) {
        return;
      }
      this.state.agents = [];
      this.state.agentsProjectId = null;
      this.byId('agentsV2AgentsList').innerHTML = '';
      this.byId('agentsV2CreateAgent').disabled = true;
      this.showError('agentsV2AgentsError', error.message || 'Agents failed to load.');
    }
  }

  renderProjects() {
    const list = this.byId('agentsV2ProjectsList');
    if (!this.state.projects.length) {
      list.innerHTML = '<div class="muted-state">No projects yet.</div>';
      return;
    }
    list.innerHTML = this.state.projects.map((project) => `
      <button class="agents-v2-list-row ${project.id === this.state.selectedProjectId ? 'selected' : ''}" type="button" data-project-id="${escapeHtml(project.id)}">
        <strong>${escapeHtml(project.name)}</strong>
      </button>
    `).join('');
    list.querySelectorAll('[data-project-id]').forEach((element) => {
      element.addEventListener('click', () => this.selectProject(element.dataset.projectId));
    });
  }

  renderAgentsEmpty() {
    this.byId('agentsV2CreateAgent').disabled = true;
    this.byId('agentsV2AgentsSubtitle').textContent = 'Select a project.';
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">No project selected.</div>';
  }

  renderAgents() {
    const list = this.byId('agentsV2AgentsList');
    if (!this.state.agents.length) {
      list.innerHTML = '<div class="muted-state">No agents yet.</div>';
      return;
    }
    list.innerHTML = this.state.agents.map((agent) => `
      <button class="agents-v2-list-row" type="button" data-agent-id="${escapeHtml(agent.id)}">
        <strong>${escapeHtml(agent.name)}</strong>
        <span>${this.dependencyLabel(agent.dependsOn || [])}</span>
      </button>
    `).join('');
    list.querySelectorAll('[data-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.openAgentModal(element.dataset.agentId));
    });
  }

  openProjectModal() {
    this.showError('agentsV2ProjectModalError', '');
    this.byId('agentsV2ProjectName').value = '';
    this.openDialog('agentsV2ProjectDialog');
    this.byId('agentsV2ProjectName')?.focus();
  }

  async submitProject(event) {
    event.preventDefault();
    if (this.state.saving) {
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2ProjectSave').disabled = true;
    this.showError('agentsV2ProjectModalError', '');
    try {
      const project = await this.api.createProject({ name: this.byId('agentsV2ProjectName').value });
      this.closeDialog('agentsV2ProjectDialog');
      await this.loadProjects();
      this.state.selectedProjectId = project.id;
      await this.selectProject(project.id);
    } catch (error) {
      this.showError('agentsV2ProjectModalError', error.message || 'Project could not be saved.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2ProjectSave').disabled = false;
    }
  }

  async openAgentModal(agentId = null) {
    if (!this.state.selectedProjectId || this.state.agentsProjectId !== this.state.selectedProjectId) {
      return;
    }
    this.state.editingAgentId = agentId;
    this.showError('agentsV2AgentModalError', '');
    this.showFieldError('');
    this.byId('agentsV2AgentModalTitle').textContent = agentId ? 'Edit Agent' : 'Create Agent';
    this.byId('agentsV2AgentName').value = '';
    this.byId('agentsV2AgentInstructions').value = '';
    this.byId('agentsV2AgentOutputJson').value = JSON.stringify(DEFAULT_OUTPUT_SCHEMA, null, 2);
    let selectedDependencies = new Set();

    if (agentId) {
      try {
        const agent = await this.api.getAgent(agentId);
        if (agent.projectId && agent.projectId !== this.state.selectedProjectId) {
          this.showError('agentsV2AgentsError', 'Agent details do not belong to the selected project.');
          return;
        }
        this.byId('agentsV2AgentName').value = agent.name || '';
        this.byId('agentsV2AgentInstructions').value = agent.instructions || '';
        this.byId('agentsV2AgentOutputJson').value = JSON.stringify(agent.outputSchema || DEFAULT_OUTPUT_SCHEMA, null, 2);
        selectedDependencies = new Set((agent.dependsOn || []).map((dependency) => dependency.id));
      } catch (error) {
        this.showError('agentsV2AgentsError', error.message || 'Agent details failed to load.');
        return;
      }
    }

    this.renderDependencyOptions(agentId, selectedDependencies);
    this.openDialog('agentsV2AgentDialog');
    this.byId('agentsV2AgentName')?.focus();
  }

  renderDependencyOptions(currentAgentId, selectedDependencies) {
    const options = this.state.agents
      .filter((agent) => agent.id !== currentAgentId)
      .map((agent) => `
        <label class="dependency-option">
          <input type="checkbox" value="${escapeHtml(agent.id)}" ${selectedDependencies.has(agent.id) ? 'checked' : ''}>
          <span>${escapeHtml(agent.name)}</span>
        </label>
      `)
      .join('');
    this.byId('agentsV2AgentDependencies').innerHTML = options || '<div class="muted-state compact">No other agents.</div>';
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
      outputSchema,
      dependsOnAgentIds: Array.from(this.document.querySelectorAll('#agentsV2AgentDependencies input[type="checkbox"]:checked')).map((input) => input.value)
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

  dependencyLabel(dependencies) {
    if (!dependencies.length) {
      return 'No dependencies';
    }
    return dependencies.map((dependency) => escapeHtml(dependency.name)).join(', ');
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

  isCurrentAgentLoad(projectId, loadSequence) {
    return this.state.selectedProjectId === projectId && this.agentLoadSequence === loadSequence;
  }

  testApi() {
    return {
      loadProjects: () => this.loadProjects(),
      selectProject: (projectId) => this.selectProject(projectId),
      openAgentModal: (agentId) => this.openAgentModal(agentId),
      parseOutputSchema: () => this.parseOutputSchema(),
      state: this.state
    };
  }
}
