import { createAgentProjectsApi } from './agent-projects-api.js';
import { escapeHtml } from './dom-render-helpers.js';
import { ProjectWorkspace } from './project-workspace.js';
import { WorkflowBuilder } from './workflow-builder.js';

const DEFAULT_OUTPUT_SCHEMA = { type: 'object', properties: {} };
const TASK_REFRESH_INTERVAL_MS = 2000;
const ACTIVE_TASK_STATUSES = new Set(['QUEUED', 'RUNNING']);

export class AgentProjectsPage {
  constructor(options = {}) {
    this.document = options.document || document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api || createAgentProjectsApi(options.http);
    this.state = {
      view: 'projects',
      projects: [],
      agents: [],
      workflows: [],
      tasks: [],
      selectedProjectId: null,
      agentsProjectId: null,
      workflowsProjectId: null,
      tasksProjectId: null,
      tasksLoadFailed: false,
      editingAgentId: null,
      openWorkflowId: null,
      runtime: null,
      runtimeError: '',
      agentModelSelection: null,
      savedAgentModelSelection: null,
      saving: false
    };
    this.projectLoadSequence = 0;
    this.workflowLoadSequence = 0;
    this.tasksPollTimer = null;
    this.tasksRequestInFlight = null;
    this.tasksRequestProjectId = null;
    this.tasksRequestLoadSequence = null;
    this.workspace = new ProjectWorkspace({
      document: this.document,
      onBack: () => this.showProjectsIndex(),
      onNewAgent: () => this.openAgentModal(),
      onEditAgent: (agentId) => this.openAgentModal(agentId),
      onNewWorkflow: () => this.openWorkflowModal(),
      onOpenWorkflow: (workflowId) => this.openWorkflowBuilder(workflowId),
      onNewTask: () => this.openTaskModal()
    });
    this.workflowBuilder = new WorkflowBuilder({
      document: this.document,
      window: this.window,
      api: this.api,
      onBack: () => this.closeWorkflowBuilder(),
      onSaved: async () => this.loadWorkflows()
    });
  }

  mount() {
    this.bind();
    this.workspace.bind();
    this.workflowBuilder.bind();
    this.showProjectsIndex({ preserveProjects: true });
    this.loadProjects();
  }

  dispose() {
    this.stopTaskPolling();
    this.workflowBuilder.dispose();
  }

  bind() {
    this.byId('agentsV2CreateProject')?.addEventListener('click', () => this.openProjectModal());
    this.byId('agentsV2ProjectCancel')?.addEventListener('click', () => this.closeDialog('agentsV2ProjectDialog'));
    this.byId('agentsV2ProjectForm')?.addEventListener('submit', (event) => this.submitProject(event));
    this.byId('agentsV2AgentCancel')?.addEventListener('click', () => this.closeDialog('agentsV2AgentDialog'));
    this.byId('agentsV2AgentForm')?.addEventListener('submit', (event) => this.submitAgent(event));
    this.byId('agentsV2AgentProvider')?.addEventListener('change', () => this.onProviderChanged());
    this.byId('agentsV2AgentModel')?.addEventListener('change', () => this.onModelChanged());
    this.byId('agentsV2AgentEffort')?.addEventListener('change', () => this.onEffortChanged());
    this.byId('agentsV2WorkflowCancel')?.addEventListener('click', () => this.closeDialog('agentsV2WorkflowDialog'));
    this.byId('agentsV2WorkflowForm')?.addEventListener('submit', (event) => this.submitWorkflow(event));
    this.byId('agentsV2TaskCancel')?.addEventListener('click', () => this.closeDialog('agentsV2TaskDialog'));
    this.byId('agentsV2TaskForm')?.addEventListener('submit', (event) => this.submitTask(event));
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

  showProjectsIndex(options = {}) {
    this.projectLoadSequence += 1;
    this.workflowLoadSequence += 1;
    this.stopTaskPolling();
    this.state.view = 'projects';
    this.state.selectedProjectId = null;
    this.state.agents = [];
    this.state.workflows = [];
    this.state.tasks = [];
    this.state.agentsProjectId = null;
    this.state.workflowsProjectId = null;
    this.state.tasksProjectId = null;
    this.state.tasksLoadFailed = false;
    this.state.openWorkflowId = null;
    this.workflowBuilder.close();
    this.byId('agentsV2ProjectsView').classList.remove('hidden');
    this.byId('agentsV2Workspace').classList.add('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    if (!options.preserveProjects) {
      this.renderProjects();
    }
  }

  async openProject(projectId) {
    const loadSequence = this.projectLoadSequence + 1;
    this.projectLoadSequence = loadSequence;
    this.workflowLoadSequence += 1;
    this.stopTaskPolling();
    this.state.view = 'project';
    this.state.selectedProjectId = projectId;
    this.state.agents = [];
    this.state.workflows = [];
    this.state.tasks = [];
    this.state.agentsProjectId = null;
    this.state.workflowsProjectId = null;
    this.state.tasksProjectId = null;
    this.state.tasksLoadFailed = false;
    this.state.openWorkflowId = null;
    this.workflowBuilder.close();
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.renderProjectWorkspace();
    this.workspace.renderLoading();
    await Promise.all([
      this.loadAgents(projectId, loadSequence),
      this.loadWorkflows(projectId, loadSequence),
      this.loadTasks(projectId, loadSequence),
      this.loadRuntimeCatalog(projectId, loadSequence)
    ]);
    if (this.isCurrentProjectLoad(projectId, loadSequence)) {
      this.renderProjectWorkspace();
    }
  }

  async loadAgents(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence) {
    if (!projectId) {
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
      this.workflowBuilder.setAgents(agents);
      this.renderProjectWorkspace();
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
      this.renderProjectWorkspace();
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

  async loadTasks(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence, options = {}) {
    if (!projectId) {
      return [];
    }
    if (this.tasksRequestInFlight && this.tasksRequestProjectId === projectId && this.tasksRequestLoadSequence === loadSequence) {
      return this.tasksRequestInFlight;
    }
    if (!options.silent) {
      this.showError('agentsV2TasksError', '');
    }
    this.state.tasksLoadFailed = false;
    const request = this.fetchTasks(projectId, loadSequence, options);
    this.tasksRequestInFlight = request;
    this.tasksRequestProjectId = projectId;
    this.tasksRequestLoadSequence = loadSequence;
    request.finally(() => {
      if (this.tasksRequestInFlight === request) {
        this.tasksRequestInFlight = null;
        this.tasksRequestProjectId = null;
        this.tasksRequestLoadSequence = null;
        if (this.isCurrentProjectLoad(projectId, loadSequence)) {
          this.syncTaskPolling();
        }
      }
    });
    return request;
  }

  async fetchTasks(projectId, loadSequence, options = {}) {
    try {
      const tasks = await this.api.listProjectTasks(projectId);
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return [];
      }
      this.state.tasks = tasks;
      this.state.tasksProjectId = projectId;
      this.state.tasksLoadFailed = false;
      this.showError('agentsV2TasksError', '');
      this.renderProjectWorkspace();
      return tasks;
    } catch (error) {
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return [];
      }
      this.state.tasks = [];
      this.state.tasksProjectId = projectId;
      this.state.tasksLoadFailed = true;
      this.byId('agentsV2TasksList').innerHTML = '';
      this.showError('agentsV2TasksError', error.message || 'Tasks failed to load.');
      this.renderProjectWorkspace();
      this.stopTaskPolling();
      return [];
    }
  }

  renderProjects() {
    const list = this.byId('agentsV2ProjectsList');
    if (!this.state.projects.length) {
      list.innerHTML = '<div class="muted-state">No projects yet.</div>';
      return;
    }
    list.innerHTML = this.state.projects.map((project) => `
      <article class="agents-v2-card project-card">
        <h3>${escapeHtml(project.name)}</h3>
        <p>Project agent configuration</p>
        <button class="button small secondary" type="button" data-project-id="${escapeHtml(project.id)}">Open project →</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-project-id]').forEach((element) => {
      element.addEventListener('click', () => this.openProject(element.dataset.projectId));
    });
  }

  renderProjectWorkspace() {
    this.workspace.render(
      this.currentProject(),
      this.state.agents,
      this.state.workflows,
      this.state.tasks,
      this.projectDataCurrent(),
      this.tasksDataCurrent(),
      this.state.tasksLoadFailed,
      this.state.runtime
    );
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
      await this.openProject(project.id);
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
    this.state.agentModelSelection = null;
    this.state.savedAgentModelSelection = null;
    if (agentId) {
      try {
        const agent = await this.api.getAgent(agentId);
        if (agent.projectId !== this.state.selectedProjectId) {
          this.showError('agentsV2AgentsError', 'Agent details do not belong to the opened project.');
          return;
        }
        this.byId('agentsV2AgentName').value = agent.name || '';
        this.byId('agentsV2AgentInstructions').value = agent.instructions || '';
        this.byId('agentsV2AgentOutputJson').value = JSON.stringify(agent.outputSchema || DEFAULT_OUTPUT_SCHEMA, null, 2);
        this.state.savedAgentModelSelection = agent.model || null;
        this.state.agentModelSelection = agent.model || null;
      } catch (error) {
        this.showError('agentsV2AgentsError', error.message || 'Agent details failed to load.');
        return;
      }
    }
    await this.loadRuntimeForAgentModal();
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
      outputSchema,
      model: this.currentValidModelSelection()
    };
    if (!request.model) {
      this.showError('agentsV2AgentModalError', this.state.runtimeError || 'Select a current ready model.');
      this.state.saving = false;
      this.byId('agentsV2AgentSave').disabled = false;
      return;
    }
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

  openTaskModal() {
    if (!this.canCreateTask()) {
      return;
    }
    this.showError('agentsV2TaskModalError', '');
    this.byId('agentsV2TaskTitle').value = '';
    this.byId('agentsV2TaskInput').value = '';
    this.renderTaskWorkflowSelect();
    this.openDialog('agentsV2TaskDialog');
  }

  renderTaskWorkflowSelect() {
    const select = this.byId('agentsV2TaskWorkflow');
    select.innerHTML = this.state.workflows
      .map((workflow) => `<option value="${escapeHtml(workflow.id)}">${escapeHtml(workflow.name)}</option>`)
      .join('');
    select.disabled = !this.state.workflows.length;
  }

  async submitTask(event) {
    event.preventDefault();
    if (this.state.saving || !this.canCreateTask()) {
      return;
    }
    const title = this.byId('agentsV2TaskTitle').value.trim();
    const input = this.byId('agentsV2TaskInput').value.trim();
    const workflowId = this.byId('agentsV2TaskWorkflow').value;
    if (!title || title.length > 120 || !input || !workflowId) {
      this.showError('agentsV2TaskModalError', 'Enter a title, task, and workflow.');
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2TaskCreateSave').disabled = true;
    this.showError('agentsV2TaskModalError', '');
    try {
      await this.api.createProjectTask(this.state.selectedProjectId, { title, input, workflowId });
      this.closeDialog('agentsV2TaskDialog');
      await this.loadTasks();
    } catch (error) {
      this.showError('agentsV2TaskModalError', error.message || 'Task could not be created.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2TaskCreateSave').disabled = false;
    }
  }

  async openWorkflowBuilder(workflowId) {
    if (!this.projectDataCurrent()) {
      return;
    }
    this.stopTaskPolling();
    const selectedProjectId = this.state.selectedProjectId;
    const projectSequence = this.projectLoadSequence;
    const workflowSequence = this.workflowLoadSequence + 1;
    this.workflowLoadSequence = workflowSequence;
    this.showError('agentsV2WorkflowsError', '');
    this.showError('agentsV2WorkflowBuilderError', '');
    try {
      const workflow = await this.api.getWorkflow(workflowId);
      if (!this.isCurrentProjectLoad(selectedProjectId, projectSequence) || this.workflowLoadSequence !== workflowSequence) {
        return;
      }
      if (workflow.projectId !== selectedProjectId) {
        this.showError('agentsV2WorkflowsError', 'Workflow does not belong to the opened project.');
        return;
      }
      this.state.view = 'workflow';
      this.state.openWorkflowId = workflow.id;
      this.byId('agentsV2ProjectsView').classList.add('hidden');
      this.byId('agentsV2Workspace').classList.add('hidden');
      this.byId('agentsV2Builder').classList.remove('hidden');
      this.workflowBuilder.open(workflow, this.currentProject(), this.state.agents);
    } catch (error) {
      if (this.workflowLoadSequence === workflowSequence) {
        this.showError('agentsV2WorkflowsError', error.message || 'Workflow failed to load.');
      }
    }
  }

  closeWorkflowBuilder() {
    this.workflowLoadSequence += 1;
    this.state.view = 'project';
    this.state.openWorkflowId = null;
    this.workflowBuilder.close();
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
    this.renderProjectWorkspace();
    this.syncTaskPolling();
  }

  syncTaskPolling() {
    if (this.shouldPollTasks()) {
      this.scheduleTaskPolling();
      return;
    }
    this.stopTaskPolling();
  }

  scheduleTaskPolling() {
    if (this.tasksPollTimer || this.tasksRequestInFlight) {
      return;
    }
    this.tasksPollTimer = this.window.setTimeout(() => {
      this.tasksPollTimer = null;
      this.pollTasks();
    }, TASK_REFRESH_INTERVAL_MS);
  }

  async pollTasks() {
    if (!this.shouldPollTasks() || this.tasksRequestInFlight) {
      this.syncTaskPolling();
      return;
    }
    await this.loadTasks(this.state.selectedProjectId, this.projectLoadSequence, { silent: true });
    this.syncTaskPolling();
  }

  stopTaskPolling() {
    if (this.tasksPollTimer) {
      this.window.clearTimeout(this.tasksPollTimer);
      this.tasksPollTimer = null;
    }
  }

  shouldPollTasks() {
    return this.state.view === 'project'
      && this.tasksDataCurrent()
      && this.state.tasks.some((task) => ACTIVE_TASK_STATUSES.has(task.executionStatus));
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

  async loadRuntimeForAgentModal() {
    this.renderModelPickerLoading();
    await this.loadRuntimeCatalog(this.state.selectedProjectId, this.projectLoadSequence, { showModalError: true });
    this.ensureInitialModelSelection();
    this.renderModelPicker();
  }

  async loadRuntimeCatalog(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence, options = {}) {
    try {
      const runtime = await this.api.getRuntime();
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.runtime = runtime;
      this.state.runtimeError = '';
    } catch (error) {
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.runtime = { providers: [] };
      this.state.runtimeError = error.message || 'Runtime catalog failed to load.';
      if (options.showModalError) {
        this.showError('agentsV2AgentModalError', this.state.runtimeError);
      }
    }
    this.renderProjectWorkspace();
  }

  renderModelPickerLoading() {
    this.showModelPickerState('');
    for (const id of ['agentsV2AgentProvider', 'agentsV2AgentModel', 'agentsV2AgentEffort']) {
      const select = this.byId(id);
      if (select) {
        select.innerHTML = '<option value="">Loading...</option>';
        select.disabled = true;
      }
    }
  }

  ensureInitialModelSelection() {
    const saved = this.state.savedAgentModelSelection;
    if (saved) {
      this.state.agentModelSelection = { ...saved };
      return;
    }
    this.state.agentModelSelection = null;
  }

  renderModelPicker() {
    const providerSelect = this.byId('agentsV2AgentProvider');
    const modelSelect = this.byId('agentsV2AgentModel');
    const effortSelect = this.byId('agentsV2AgentEffort');
    const selection = this.state.agentModelSelection || {};
    const readyProviders = this.readyProviders();
    const saved = this.state.savedAgentModelSelection;
    const savedProvider = this.runtimeCatalogProvider(saved?.providerId);
    const savedProviderReady = savedProvider?.status === 'READY';
    providerSelect.disabled = !readyProviders.length;
    providerSelect.innerHTML = [
      '<option value="">Select provider</option>',
      ...readyProviders.map((provider) => `<option value="${escapeHtml(provider.providerId)}">${escapeHtml(provider.displayName || provider.providerId)}</option>`),
      saved?.providerId && !savedProviderReady
        ? `<option value="${escapeHtml(saved.providerId)}" disabled>${escapeHtml(this.savedProviderLabel(saved, savedProvider))}</option>`
        : ''
    ].join('');
    providerSelect.value = selection.providerId || '';
    this.renderModelPickerState(readyProviders, savedProvider);

    const provider = this.runtimeProvider(selection.providerId);
    const models = provider?.models || [];
    const selectedProvider = this.runtimeCatalogProvider(selection.providerId);
    const modelQualifier = this.unavailableSelectionQualifier(selectedProvider);
    modelSelect.disabled = !provider || !models.length;
    modelSelect.innerHTML = [
      '<option value="">Select model</option>',
      ...models.map((model) => `<option value="${escapeHtml(model.modelId)}">${escapeHtml(model.displayName || model.modelId)}</option>`),
      saved?.modelId && selection.providerId === saved.providerId && !models.some((model) => model.modelId === saved.modelId)
        ? `<option value="${escapeHtml(saved.modelId)}" disabled>${escapeHtml(`${saved.modelId} (${modelQualifier})`)}</option>`
        : ''
    ].join('');
    modelSelect.value = selection.modelId || '';

    const model = models.find((candidate) => candidate.modelId === selection.modelId);
    const efforts = model?.efforts || [];
    const effortQualifier = modelQualifier;
    effortSelect.disabled = !model || !efforts.length;
    effortSelect.innerHTML = [
      efforts.length ? '<option value="">Select effort</option>' : '<option value="">No effort</option>',
      ...efforts.map((effort) => `<option value="${escapeHtml(effort.effortId)}">${escapeHtml(this.formatEffortLabel(effort))}</option>`),
      saved?.effortId && selection.modelId === saved.modelId && !efforts.some((effort) => effort.effortId === saved.effortId)
        ? `<option value="${escapeHtml(saved.effortId)}" disabled>${escapeHtml(`${saved.effortId} (${effortQualifier})`)}</option>`
        : ''
    ].join('');
    effortSelect.value = selection.effortId || '';
  }

  onProviderChanged() {
    const providerId = this.byId('agentsV2AgentProvider').value || null;
    this.state.agentModelSelection = providerId ? { providerId, modelId: null, effortId: null } : null;
    this.renderModelPicker();
  }

  onModelChanged() {
    const providerId = this.byId('agentsV2AgentProvider').value || null;
    const modelId = this.byId('agentsV2AgentModel').value || null;
    const model = this.runtimeProvider(providerId)?.models?.find((candidate) => candidate.modelId === modelId);
    const effort = model?.efforts?.length === 1 ? model.efforts[0] : null;
    this.state.agentModelSelection = providerId && modelId
      ? { providerId, modelId, effortId: effort?.effortId || null }
      : null;
    this.renderModelPicker();
  }

  onEffortChanged() {
    const selection = this.state.agentModelSelection;
    if (!selection) {
      return;
    }
    this.state.agentModelSelection = {
      ...selection,
      effortId: this.byId('agentsV2AgentEffort').value || null
    };
  }

  currentValidModelSelection() {
    const selection = this.state.agentModelSelection;
    if (!selection?.providerId || !selection?.modelId) {
      return null;
    }
    const model = this.runtimeProvider(selection.providerId)?.models?.find((candidate) => candidate.modelId === selection.modelId);
    if (!model) {
      return null;
    }
    const efforts = model.efforts || [];
    if (!efforts.length) {
      return { providerId: selection.providerId, modelId: selection.modelId, effortId: null };
    }
    if (!selection.effortId || !efforts.some((effort) => effort.effortId === selection.effortId)) {
      return null;
    }
    return { providerId: selection.providerId, modelId: selection.modelId, effortId: selection.effortId };
  }

  formatEffortLabel(effort) {
    if (!effort?.description || effort.description === effort.effortId) {
      return effort?.effortId || '';
    }
    return `${effort.effortId} - ${effort.description}`;
  }

  renderModelPickerState(readyProviders, savedProvider) {
    const element = this.byId('agentsV2AgentRuntimeState');
    if (!element) {
      return;
    }
    let message = '';
    if (!readyProviders.length) {
      if (savedProvider && savedProvider.status !== 'READY') {
        message = `${savedProvider.displayName || savedProvider.providerId} runtime ${String(savedProvider.status || '').toLowerCase()}.`;
      } else {
        message = this.state.runtimeError || 'No ready model providers available.';
      }
    }
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  showModelPickerState(message) {
    const element = this.byId('agentsV2AgentRuntimeState');
    if (!element) {
      return;
    }
    element.textContent = message;
    element.classList.toggle('hidden', !message);
  }

  savedProviderLabel(saved, provider) {
    if (provider) {
      return `${provider.displayName || provider.providerId} (${String(provider.status || '').toLowerCase()})`;
    }
    return `${saved.providerId} (stale)`;
  }

  unavailableSelectionQualifier(provider) {
    if (provider && provider.status !== 'READY') {
      return String(provider.status || '').toLowerCase();
    }
    return 'stale';
  }

  readyProviders() {
    return (this.state.runtime?.providers || []).filter((provider) => provider.status === 'READY');
  }

  runtimeCatalogProvider(providerId) {
    if (!providerId) {
      return null;
    }
    return (this.state.runtime?.providers || []).find((provider) => provider.providerId === providerId) || null;
  }

  runtimeProvider(providerId) {
    return this.readyProviders().find((provider) => provider.providerId === providerId);
  }

  projectDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.agentsProjectId === this.state.selectedProjectId
      && this.state.workflowsProjectId === this.state.selectedProjectId;
  }

  tasksDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.tasksProjectId === this.state.selectedProjectId;
  }

  canCreateTask() {
    return this.projectDataCurrent() && this.tasksDataCurrent() && this.state.workflows.length > 0;
  }

  isCurrentProjectLoad(projectId, loadSequence) {
    return this.state.selectedProjectId === projectId && this.projectLoadSequence === loadSequence;
  }

  currentProject() {
    return this.state.projects.find((project) => project.id === this.state.selectedProjectId);
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
      openProject: (projectId) => this.openProject(projectId),
      selectProject: (projectId) => this.openProject(projectId),
      showProjectsIndex: () => this.showProjectsIndex(),
      openAgentModal: (agentId) => this.openAgentModal(agentId),
      openTaskModal: () => this.openTaskModal(),
      loadTasks: () => this.loadTasks(),
      openWorkflowBuilder: (workflowId) => this.openWorkflowBuilder(workflowId),
      addNode: (agentId) => this.workflowBuilder.addNode(agentId),
      removeConnection: (sourceNodeId, targetNodeId) => this.workflowBuilder.removeConnection(sourceNodeId, targetNodeId),
      removeNode: (nodeId) => this.workflowBuilder.removeNode(nodeId),
      saveWorkflow: () => this.workflowBuilder.save(),
      parseOutputSchema: () => this.parseOutputSchema(),
      state: this.state,
      workflowBuilder: this.workflowBuilder
    };
  }
}
