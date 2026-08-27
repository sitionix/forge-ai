import { createAgentProjectsApi } from './agent-projects-api.js';
import { escapeHtml } from './dom-render-helpers.js';
import { ProjectWorkspace } from './project-workspace.js';
import { ProjectLogsView } from './project-logs-view.js';
import { TaskExecutionView } from './task-execution-view.js';
import { WorkflowBuilder } from './workflow-builder.js';

const DEFAULT_OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    result: { type: 'string' }
  },
  required: ['result'],
  additionalProperties: false
};
const ACTIVE_TASK_STATUSES = new Set(['QUEUED', 'RUNNING']);
const JSON_SCHEMA_TYPES = new Set(['object', 'array', 'string', 'number', 'integer', 'boolean', 'null']);

export class AgentProjectsPage {
  constructor(options = {}) {
    this.document = options.document || document;
    this.window = options.window || this.document.defaultView || window;
    this.api = options.api || createAgentProjectsApi(options.http);
    this.runtimeConfig = options.runtimeConfig || {};
    this.taskPollIntervalMs = Number(this.runtimeConfig.activeJobPollIntervalMs) || 2000;
    this.disposed = false;
    this.state = {
      view: 'projects',
      projects: [],
      repositories: [],
      services: [],
      agents: [],
      workflows: [],
      tasks: [],
      tasksPage: 0,
      tasksPageSize: 20,
      tasksLoadedPage: null,
      tasksTotalItems: 0,
      tasksTotalPages: 0,
      selectedProjectId: null,
      openServiceId: null,
      repositoriesProjectId: null,
      agentsProjectId: null,
      workflowsProjectId: null,
      tasksProjectId: null,
      repositoriesLoadFailed: false,
      tasksLoadFailed: false,
      editingAgentId: null,
      openWorkflowId: null,
      runtime: null,
      runtimeError: '',
      agentModelSelection: null,
      savedAgentModelSelection: null,
      openTaskId: null,
      cloningRepositoryIds: new Set(),
      refreshingRepositoryIds: new Set(),
      pullingRepositoryIds: new Set(),
      saving: false
    };
    this.projectLoadSequence = 0;
    this.workflowLoadSequence = 0;
    this.taskPollTimer = null;
    this.taskPollInFlight = null;
    this.taskPollProjectId = null;
    this.taskPollLoadSequence = null;
    this.taskPollPage = null;
    this.workspace = new ProjectWorkspace({
      document: this.document,
      onBack: () => this.showProjectsIndex(),
      onOpenLogs: () => this.openLogsWorkspace(this.state.selectedProjectId),
      onAddService: () => this.createService(),
      onOpenService: (serviceId) => this.openServiceWorkspace(this.state.selectedProjectId, serviceId),
      onImportRepository: () => this.openRepositoryModal(),
      onCloneRepository: (repositoryId) => this.cloneRepository(repositoryId),
      onRefreshRepository: (repositoryId) => this.refreshRepository(repositoryId),
      onPullRepository: (repositoryId) => this.pullRepository(repositoryId),
      onNewAgent: () => this.openAgentModal(),
      onEditAgent: (agentId) => this.openAgentModal(agentId),
      onNewWorkflow: () => this.openWorkflowModal(),
      onOpenWorkflow: (workflowId) => this.openWorkflowBuilder(workflowId),
      onDeleteAgent: (agentId) => this.deleteAgent(agentId),
      onDeleteWorkflow: (workflowId) => this.deleteWorkflow(workflowId),
      onNewTask: () => this.openTaskModal(),
      onOpenTask: (taskId) => this.openTaskExecution(taskId),
      onDeleteTask: (taskId) => this.deleteProjectTask(taskId),
      onTaskPage: (page) => this.goToTaskPage(page)
    });
    this.taskExecutionView = new TaskExecutionView({
      document: this.document,
      window: this.window,
      api: this.api,
      runtimeConfig: this.runtimeConfig,
      onBack: () => this.closeTaskExecution()
    });
    this.workflowBuilder = new WorkflowBuilder({
      document: this.document,
      window: this.window,
      api: this.api,
      onBack: () => this.closeWorkflowBuilder(),
      onSaved: async () => this.loadWorkflows()
    });
    this.logsView = null;
    this.onPopState = () => this.syncRoute();
  }

  mount() {
    this.disposed = false;
    this.bind();
    this.workspace.bind();
    this.taskExecutionView.bind();
    this.workflowBuilder.bind();
    this.window.addEventListener('popstate', this.onPopState);
    this.showProjectsIndex({ preserveProjects: true });
    this.loadProjects();
  }

  dispose() {
    this.disposed = true;
    this.stopTaskPolling();
    this.taskExecutionView.dispose();
    this.workflowBuilder.dispose();
    this.disposeLogsWorkspace();
    this.window.removeEventListener('popstate', this.onPopState);
  }

  bind() {
    this.byId('agentsV2CreateProject')?.addEventListener('click', () => this.openProjectModal());
    this.byId('agentsV2ProjectCancel')?.addEventListener('click', () => this.closeDialog('agentsV2ProjectDialog'));
    this.byId('agentsV2ProjectForm')?.addEventListener('submit', (event) => this.submitProject(event));
    this.byId('agentsV2RepositoryCancel')?.addEventListener('click', () => this.closeDialog('agentsV2RepositoryDialog'));
    this.byId('agentsV2RepositoryForm')?.addEventListener('submit', (event) => this.submitRepository(event));
    this.byId('agentsV2AgentCancel')?.addEventListener('click', () => this.closeDialog('agentsV2AgentDialog'));
    this.byId('agentsV2AgentForm')?.addEventListener('submit', (event) => this.submitAgent(event));
    this.byId('agentsV2AgentOutputJson')?.addEventListener('input', () => this.showFieldError(''));
    this.byId('agentsV2AgentOutputFormat')?.addEventListener('click', () => this.formatOutputSchemaJson());
    this.byId('agentsV2AgentOutputTemplate')?.addEventListener('click', () => this.applyOutputSchemaTemplate());
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
      await this.syncRoute();
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
    this.state.repositories = [];
    this.state.services = [];
    this.state.agents = [];
    this.state.workflows = [];
    this.state.tasks = [];
    this.resetTaskPage();
    this.state.repositoriesProjectId = null;
    this.state.agentsProjectId = null;
    this.state.workflowsProjectId = null;
    this.state.tasksProjectId = null;
    this.state.repositoriesLoadFailed = false;
    this.state.tasksLoadFailed = false;
    this.state.openWorkflowId = null;
    this.state.openTaskId = null;
    this.state.cloningRepositoryIds.clear();
    this.state.refreshingRepositoryIds.clear();
    this.state.pullingRepositoryIds.clear();
    this.taskExecutionView.close();
    this.workflowBuilder.close();
    this.disposeLogsWorkspace();
    this.byId('projectLogsWorkspace').classList.add('hidden');
    this.byId('agentsV2ProjectsView').classList.remove('hidden');
    this.byId('agentsV2Workspace').classList.add('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2TaskExecution').classList.add('hidden');
    if (!options.preserveProjects) {
      this.renderProjects();
    }
  }

  async openProject(projectId) {
    this.disposeLogsWorkspace();
    const loadSequence = this.projectLoadSequence + 1;
    this.projectLoadSequence = loadSequence;
    this.workflowLoadSequence += 1;
    this.stopTaskPolling();
    this.state.view = 'project';
    this.state.selectedProjectId = projectId;
    this.state.repositories = [];
    this.state.services = [];
    this.state.agents = [];
    this.state.workflows = [];
    this.state.tasks = [];
    this.resetTaskPage();
    this.state.repositoriesProjectId = null;
    this.state.agentsProjectId = null;
    this.state.workflowsProjectId = null;
    this.state.tasksProjectId = null;
    this.state.repositoriesLoadFailed = false;
    this.state.tasksLoadFailed = false;
    this.state.openWorkflowId = null;
    this.state.openTaskId = null;
    this.state.cloningRepositoryIds.clear();
    this.state.refreshingRepositoryIds.clear();
    this.state.pullingRepositoryIds.clear();
    this.taskExecutionView.close();
    this.workflowBuilder.close();
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2TaskExecution').classList.add('hidden');
    this.byId('projectLogsWorkspace').classList.add('hidden');
    this.renderProjectWorkspace();
    this.workspace.renderLoading();
    await Promise.all([
      this.loadRepositories(projectId, loadSequence),
      this.loadServices(projectId, loadSequence),
      this.loadAgents(projectId, loadSequence),
      this.loadWorkflows(projectId, loadSequence),
      this.loadTasks(projectId, loadSequence, { page: 0 }),
      this.loadRuntimeCatalog(projectId, loadSequence)
    ]);
    if (!this.disposed && this.isCurrentProjectLoad(projectId, loadSequence)) {
      this.renderProjectWorkspace();
    }
  }

  async openLogsWorkspace(projectId, options = {}) {
    if (!projectId) {
      return;
    }
    this.stopTaskPolling();
    this.taskExecutionView.close();
    this.workflowBuilder.close();
    this.disposeLogsWorkspace();
    this.state.view = 'logs';
    this.state.selectedProjectId = projectId;
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.add('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2TaskExecution').classList.add('hidden');
    this.byId('projectLogsWorkspace').classList.remove('hidden');
    this.byId('serviceOverview').classList.add('hidden');
    const currentProject = this.state.projects.find((project) => project.id === projectId);
    const projectName = currentProject?.name || 'Project';
    this.byId('projectLogsTitle').textContent = `${projectName} Logs`;
    this.byId('projectLogsCrumbs').textContent = `Projects / ${projectName} / Logs`;
    this.byId('projectLogsBack').onclick = () => this.returnToProject();
    if (options.pushState !== false) {
      this.window.history.pushState(
        { projectId, view: 'logs' },
        '',
        this.pageUrl(`#/projects/${encodeURIComponent(projectId)}/logs`)
      );
    }
    this.logsView = new ProjectLogsView({ document: this.document, window: this.window, api: this.api });
    this.logsView.bind();
    await this.logsView.load(projectId);
  }

  async loadServices(projectId=this.state.selectedProjectId, loadSequence=this.projectLoadSequence){
    if(!projectId||!this.api.listServices)return;
    try{const services=await this.api.listServices(projectId);if(this.isCurrentProjectLoad(projectId,loadSequence)){this.state.services=services;this.workspace.renderServices(services,this.state.repositories);}}catch{if(this.isCurrentProjectLoad(projectId,loadSequence)){this.state.services=[];this.workspace.renderServices([]);}}
  }

  async createService(){
    const projectId=this.state.selectedProjectId;if(!projectId)return;
    const name=this.window.prompt?.('Service name');if(!name)return;
    const provider=(this.window.prompt?.('Runtime provider: DOCKER or SYSTEMD','DOCKER')||'DOCKER').toUpperCase();
    const identity=this.window.prompt?.(provider==='SYSTEMD'?'Systemd unit':'Docker container');if(!identity)return;
    await this.api.createService(projectId,{name,repositoryId:null,runtimeTarget:{connection:'LOCAL',sshConnectionId:null,provider,container:provider==='DOCKER'?identity:null,unit:provider==='SYSTEMD'?identity:null}});
    await this.loadServices(projectId,this.projectLoadSequence);
  }

  async openServiceWorkspace(projectId,serviceId,options={}){
    if(!projectId||!serviceId)return;this.disposeLogsWorkspace();this.state.view='service';this.state.selectedProjectId=projectId;this.state.openServiceId=serviceId;
    this.byId('agentsV2ProjectsView').classList.add('hidden');this.byId('agentsV2Workspace').classList.add('hidden');this.byId('projectLogsWorkspace').classList.remove('hidden');this.byId('serviceOverview').classList.remove('hidden');
    const [service,runtime,sources]=await Promise.all([this.api.getService(projectId,serviceId),this.api.getServiceRuntime(projectId,serviceId),this.api.listServiceLogSources(projectId,serviceId)]);
    this.byId('projectLogsTitle').textContent=service.name;this.byId('projectLogsCrumbs').textContent=`Projects / ${this.currentProject()?.name||'Project'} / Services / ${service.name}`;this.byId('serviceRuntimeStatus').textContent=runtime.status;
    this.byId('serviceOverviewSummary').textContent=`${runtime.connection} · ${runtime.provider} · ${runtime.targetIdentity} · ${sources.length} log source${sources.length===1?'':'s'}`;
    this.byId('serviceRuntimeDetails').innerHTML=Object.entries(runtime.metadata||{}).map(([k,v])=>`<div><strong>${escapeHtml(k)}</strong>: ${escapeHtml(v)}</div>`).join('')+(runtime.uptime?`<div><strong>Uptime</strong>: ${escapeHtml(runtime.uptime)}</div>`:'');
    this.byId('projectLogsBack').onclick=()=>this.returnToProject();
    const scoped={...this.api,listLogSources:()=>Promise.resolve(sources)};this.logsView=new ProjectLogsView({document:this.document,window:this.window,api:scoped});this.logsView.bind();await this.logsView.load(projectId);
    if(options.pushState!==false)this.window.history.pushState({projectId,serviceId,view:'service'},'',this.pageUrl(`#/projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(serviceId)}`));
  }

  async returnToProject(options = {}) {
    const projectId = this.state.selectedProjectId;
    this.disposeLogsWorkspace();
    this.byId('projectLogsWorkspace').classList.add('hidden');
    this.byId('serviceOverview').classList.add('hidden');
    if (options.pushState !== false) {
      this.window.history.pushState({ projectId, view: 'project' }, '', this.pageUrl());
    }
    if (projectId) {
      await this.openProject(projectId);
    } else {
      this.showProjectsIndex();
    }
  }

  async syncRoute() {
    const serviceMatch=this.window.location.hash.match(/^#\/projects\/([^/]+)\/services\/([^/]+)\/?$/);if(serviceMatch){const p=decodeURIComponent(serviceMatch[1]),s=decodeURIComponent(serviceMatch[2]);if(this.state.view!=='service'||this.state.openServiceId!==s)await this.openServiceWorkspace(p,s,{pushState:false});return;}
    const match = this.window.location.hash.match(/^#\/projects\/([^/]+)\/logs\/?$/);
    if (match) {
      const projectId = decodeURIComponent(match[1]);
      if (this.state.view !== 'logs' || this.state.selectedProjectId !== projectId) {
        await this.openLogsWorkspace(projectId, { pushState: false });
      }
      return;
    }
    if (this.state.view === 'logs') {
      await this.returnToProject({ pushState: false });
    }
  }

  pageUrl(hash = '') {
    return `${this.window.location.pathname}${this.window.location.search}${hash}`;
  }

  disposeLogsWorkspace() {
    this.logsView?.dispose();
    this.logsView = null;
    const back = this.byId('projectLogsBack');
    if (back) back.onclick = null;
  }

  async loadRepositories(projectId = this.state.selectedProjectId, loadSequence = this.projectLoadSequence) {
    if (!projectId) {
      return;
    }
    this.showError('agentsV2RepositoriesError', '');
    this.state.repositoriesLoadFailed = false;
    try {
      const repositories = await this.api.listProjectRepositories(projectId);
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.repositories = repositories;
      this.state.repositoriesProjectId = projectId;
      this.renderProjectWorkspace();
    } catch (error) {
      if (!this.isCurrentProjectLoad(projectId, loadSequence)) {
        return;
      }
      this.state.repositories = [];
      this.state.repositoriesProjectId = projectId;
      this.state.repositoriesLoadFailed = true;
      this.byId('agentsV2RepositoriesList').innerHTML = '';
      this.showError('agentsV2RepositoriesError', error.message || 'Repositories failed to load.');
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
    if (!projectId || this.disposed) {
      return [];
    }
    const page = options.page ?? this.state.tasksPage;
    if (
      !options.force
      && this.taskPollInFlight
      && this.taskPollProjectId === projectId
      && this.taskPollLoadSequence === loadSequence
      && this.taskPollPage === page
    ) {
      return this.taskPollInFlight;
    }
    if (!options.background) {
      this.showError('agentsV2TasksError', '');
      this.state.tasksLoadFailed = false;
    }
    const request = this.fetchTasks(projectId, loadSequence, options);
    this.taskPollInFlight = request;
    this.taskPollProjectId = projectId;
    this.taskPollLoadSequence = loadSequence;
    this.taskPollPage = page;
    request.finally(() => {
      if (this.taskPollInFlight === request) {
        this.taskPollInFlight = null;
        this.taskPollProjectId = null;
        this.taskPollLoadSequence = null;
        this.taskPollPage = null;
        if (!this.disposed && this.isCurrentProjectLoad(projectId, loadSequence)) {
          this.syncTaskPolling();
        }
      }
    });
    return request;
  }

  async fetchTasks(projectId, loadSequence, options = {}) {
    try {
      const page = options.page ?? this.state.tasksPage;
      const tasksPage = await this.api.listProjectTasks(projectId, page, this.state.tasksPageSize);
      if (this.disposed || !this.isCurrentTaskLoad(projectId, loadSequence, page)) {
        return [];
      }
      this.state.tasks = tasksPage.items || [];
      this.state.tasksProjectId = projectId;
      this.state.tasksLoadedPage = page;
      this.state.tasksTotalItems = Number(tasksPage.totalItems) || 0;
      this.state.tasksTotalPages = Number(tasksPage.totalPages) || 0;
      this.state.tasksLoadFailed = false;
      this.showError('agentsV2TasksError', '');
      this.renderProjectWorkspace();
      return this.state.tasks;
    } catch (error) {
      const page = options.page ?? this.state.tasksPage;
      if (this.disposed || !this.isCurrentTaskLoad(projectId, loadSequence, page)) {
        return [];
      }
      const currentTasks = Array.isArray(this.state.tasks) ? this.state.tasks : [];
      if (options.background && this.state.tasksProjectId === projectId && this.state.tasksLoadedPage === page && currentTasks.length) {
        this.showError('agentsV2TasksError', error.message || 'Tasks refresh failed.');
        this.renderProjectWorkspace();
        return currentTasks;
      }
      this.state.tasks = [];
      this.state.tasksProjectId = projectId;
      this.state.tasksLoadedPage = page;
      this.state.tasksTotalItems = 0;
      this.state.tasksTotalPages = 0;
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
      <article class="agents-v2-card project-card agents-v2-deletable">
        <button
          class="entity-delete-control"
          type="button"
          data-delete-project-id="${escapeHtml(project.id)}"
          aria-label="Delete project ${escapeHtml(project.name)}"
          title="Delete project"
        >×</button>
        <h3>${escapeHtml(project.name)}</h3>
        <p>Project agent configuration</p>
        <div class="agents-v2-card-actions">
          <button class="button small secondary" type="button" data-project-id="${escapeHtml(project.id)}">Open project →</button>
        </div>
      </article>
    `).join('');
    list.querySelectorAll('[data-project-id]').forEach((element) => {
      element.addEventListener('click', () => this.openProject(element.dataset.projectId));
    });
    list.querySelectorAll('[data-delete-project-id]').forEach((element) => {
      element.addEventListener('click', () => this.deleteProject(element.dataset.deleteProjectId));
    });
  }

  renderProjectWorkspace() {
    this.workspace.render(
      this.currentProject(),
      this.state.repositories,
      this.state.agents,
      this.state.workflows,
      this.state.tasks,
      this.repositoriesDataCurrent(),
      this.projectDataCurrent(),
      this.workflowsDataCurrent(),
      this.tasksDataCurrent(),
      this.state.repositoriesLoadFailed,
      this.state.tasksLoadFailed,
      this.state.runtime,
      this.currentTaskPage(),
      this.state.cloningRepositoryIds,
      this.state.pullingRepositoryIds,
      this.state.refreshingRepositoryIds
    );
    this.workspace.renderServices(this.state.services,this.state.repositories);
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

  openRepositoryModal() {
    if (!this.repositoriesDataCurrent()) {
      return;
    }
    this.showError('agentsV2RepositoryModalError', '');
    this.byId('agentsV2RepositoryUrl').value = '';
    this.openDialog('agentsV2RepositoryDialog');
  }

  async submitRepository(event) {
    event.preventDefault();
    if (this.state.saving || !this.repositoriesDataCurrent()) {
      return;
    }
    const remoteUrl = this.byId('agentsV2RepositoryUrl').value.trim();
    if (!remoteUrl) {
      this.showError('agentsV2RepositoryModalError', 'Repository URL is required.');
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2RepositoryImport').disabled = true;
    this.showError('agentsV2RepositoryModalError', '');
    try {
      await this.api.importProjectRepository(this.state.selectedProjectId, { remoteUrl });
      this.closeDialog('agentsV2RepositoryDialog');
      await this.loadRepositories(this.state.selectedProjectId, this.projectLoadSequence);
    } catch (error) {
      this.showError('agentsV2RepositoryModalError', error.message || 'Repository could not be imported.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2RepositoryImport').disabled = false;
    }
  }

  async cloneRepository(repositoryId) {
    if (!this.repositoriesDataCurrent() || this.state.cloningRepositoryIds.has(repositoryId)) {
      return;
    }
    this.state.cloningRepositoryIds.add(repositoryId);
    this.showError('agentsV2RepositoriesError', '');
    this.renderProjectWorkspace();
    try {
      await this.api.cloneProjectRepository(this.state.selectedProjectId, repositoryId);
      await this.loadRepositories(this.state.selectedProjectId, this.projectLoadSequence);
    } catch (error) {
      this.showError('agentsV2RepositoriesError', error.message || 'Repository could not be cloned.');
    } finally {
      this.state.cloningRepositoryIds.delete(repositoryId);
      this.renderProjectWorkspace();
    }
  }

  async pullRepository(repositoryId) {
    if (!this.repositoriesDataCurrent() || this.state.pullingRepositoryIds.has(repositoryId)) {
      return;
    }
    const repository = this.state.repositories.find((candidate) => candidate.id === repositoryId);
    if (!repository?.cloned || !repository.git?.pullAvailable) {
      return;
    }
    this.state.pullingRepositoryIds.add(repositoryId);
    this.showError('agentsV2RepositoriesError', '');
    this.renderProjectWorkspace();
    try {
      await this.api.pullProjectRepository(this.state.selectedProjectId, repositoryId);
      await this.loadRepositories(this.state.selectedProjectId, this.projectLoadSequence);
    } catch (error) {
      await this.loadRepositories(this.state.selectedProjectId, this.projectLoadSequence);
      this.showError('agentsV2RepositoriesError', error.message || 'Repository could not be pulled.');
    } finally {
      this.state.pullingRepositoryIds.delete(repositoryId);
      this.renderProjectWorkspace();
    }
  }

  async refreshRepository(repositoryId) {
    if (!this.repositoriesDataCurrent() || this.state.refreshingRepositoryIds.has(repositoryId)) {
      return;
    }
    const repository = this.state.repositories.find((candidate) => candidate.id === repositoryId);
    if (!repository?.cloned) {
      return;
    }
    this.state.refreshingRepositoryIds.add(repositoryId);
    this.showError('agentsV2RepositoriesError', '');
    this.renderProjectWorkspace();
    try {
      await this.api.refreshProjectRepository(this.state.selectedProjectId, repositoryId);
      await this.loadRepositories(this.state.selectedProjectId, this.projectLoadSequence);
    } catch (error) {
      this.showError('agentsV2RepositoriesError', error.message || 'Repository remote state could not be refreshed.');
    } finally {
      this.state.refreshingRepositoryIds.delete(repositoryId);
      this.renderProjectWorkspace();
    }
  }

  repositoriesDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.repositoriesProjectId === this.state.selectedProjectId
      && !this.state.repositoriesLoadFailed;
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
    this.renderTaskRepositorySelect();
    this.openDialog('agentsV2TaskDialog');
  }

  renderTaskWorkflowSelect() {
    const select = this.byId('agentsV2TaskWorkflow');
    select.innerHTML = this.state.workflows
      .map((workflow) => `<option value="${escapeHtml(workflow.id)}">${escapeHtml(workflow.name)}</option>`)
      .join('');
    select.disabled = !this.state.workflows.length;
  }

  renderTaskRepositorySelect() {
    this.byId('agentsV2TaskRepositories').innerHTML = [...this.state.repositories]
      .sort((left, right) => (left.name || '').localeCompare(right.name || ''))
      .map((repository) => `
        <label class="agents-v2-checkbox-option">
          <input type="checkbox" name="repositoryIds" value="${escapeHtml(repository.id)}">
          <span>${escapeHtml(repository.name)}</span>
        </label>`)
      .join('');
  }

  async submitTask(event) {
    event.preventDefault();
    if (this.state.saving || !this.canCreateTask()) {
      return;
    }
    const title = this.byId('agentsV2TaskTitle').value.trim();
    const input = this.byId('agentsV2TaskInput').value.trim();
    const workflowId = this.byId('agentsV2TaskWorkflow').value;
    const selectedRepositoryIds = new Set(Array.from(
      this.byId('agentsV2TaskRepositories').querySelectorAll('input[name="repositoryIds"]:checked'),
      (input) => input.value
    ));
    const repositoryIds = this.state.repositories
      .map((repository) => repository.id)
      .filter((repositoryId) => selectedRepositoryIds.has(repositoryId));
    if (!title || title.length > 120 || !input || !workflowId || !repositoryIds.length) {
      this.showError('agentsV2TaskModalError', 'Enter a title, task, workflow, and select at least one repository.');
      return;
    }
    this.state.saving = true;
    this.byId('agentsV2TaskCreateSave').disabled = true;
    this.showError('agentsV2TaskModalError', '');
    try {
      await this.api.createProjectTask(this.state.selectedProjectId, { title, input, workflowId, repositoryIds });
      this.closeDialog('agentsV2TaskDialog');
      this.state.tasksPage = 0;
      if (this.taskPollInFlight) {
        await this.taskPollInFlight;
      }
      await this.loadTasks(this.state.selectedProjectId, this.projectLoadSequence, { force: true, page: 0 });
    } catch (error) {
      this.showError('agentsV2TaskModalError', error.message || 'Task could not be created.');
    } finally {
      this.state.saving = false;
      this.byId('agentsV2TaskCreateSave').disabled = false;
    }
  }

  async deleteProject(projectId) {
    const project = this.state.projects.find((candidate) => candidate.id === projectId);
    const name = project?.name || 'Project';
    if (!this.window.confirm(`Delete project "${name}"?\nIts agents, workflows, tasks and execution history will be deleted.`)) {
      return;
    }
    this.showError('agentsV2ProjectsError', '');
    try {
      await this.api.deleteProject(projectId);
      if (this.state.selectedProjectId === projectId) {
        this.showProjectsIndex({ preserveProjects: true });
      }
      await this.loadProjects();
    } catch (error) {
      this.showError('agentsV2ProjectsError', error.message || 'Project could not be deleted.');
    }
  }

  async deleteAgent(agentId) {
    const agent = this.state.agents.find((candidate) => candidate.id === agentId);
    const name = agent?.name || 'Agent';
    if (!this.window.confirm(`Delete agent "${name}"?`)) {
      return;
    }
    this.showError('agentsV2AgentsError', '');
    try {
      await this.api.deleteAgent(agentId);
      if (this.state.editingAgentId === agentId) {
        this.closeDialog('agentsV2AgentDialog');
        this.state.editingAgentId = null;
      }
      await this.loadAgents();
    } catch (error) {
      this.showError('agentsV2AgentsError', error.message || 'Agent could not be deleted.');
    }
  }

  async deleteWorkflow(workflowId) {
    const workflow = this.state.workflows.find((candidate) => candidate.id === workflowId);
    const name = workflow?.name || 'Workflow';
    if (!this.window.confirm(`Delete workflow "${name}"?`)) {
      return;
    }
    this.showError('agentsV2WorkflowsError', '');
    try {
      await this.api.deleteWorkflow(workflowId);
      if (this.state.openWorkflowId === workflowId) {
        this.closeWorkflowBuilder();
      }
      await this.loadWorkflows();
      await this.loadTasks(this.state.selectedProjectId, this.projectLoadSequence, { force: true, page: this.state.tasksPage });
    } catch (error) {
      this.showError('agentsV2WorkflowsError', error.message || 'Workflow could not be deleted.');
    }
  }

  async deleteProjectTask(taskId) {
    const task = this.state.tasks.find((candidate) => candidate.id === taskId);
    const title = task?.title || 'Task';
    if (!this.window.confirm(`Delete task "${title}"?`)) {
      return;
    }
    const projectId = this.state.selectedProjectId;
    const loadSequence = this.projectLoadSequence;
    const page = this.state.tasksPage;
    this.showError('agentsV2TasksError', '');
    try {
      await this.api.deleteProjectTask(taskId);
      if (this.taskPollInFlight && this.taskPollProjectId === projectId && this.taskPollLoadSequence === loadSequence && this.taskPollPage === page) {
        await this.taskPollInFlight;
      }
      const currentPageItems = await this.loadTasks(projectId, loadSequence, { force: true, page });
      if (!this.disposed && this.state.view === 'project' && this.isCurrentTaskLoad(projectId, loadSequence, page)
          && page > 0 && currentPageItems.length === 0) {
        this.state.tasksPage = page - 1;
        await this.loadTasks(projectId, loadSequence, { force: true, page: this.state.tasksPage });
      }
    } catch (error) {
      this.showError('agentsV2TasksError', error.message || 'Task could not be deleted.');
      this.renderProjectWorkspace();
    }
  }

  async goToTaskPage(page) {
    if (!this.state.selectedProjectId || this.disposed) {
      return;
    }
    const totalPages = Number(this.state.tasksTotalPages) || 0;
    if (page < 0 || (totalPages > 0 && page >= totalPages) || page === this.state.tasksPage) {
      return;
    }
    this.state.tasksPage = page;
    this.renderProjectWorkspace();
    await this.loadTasks(this.state.selectedProjectId, this.projectLoadSequence, { force: true, page });
  }

  async openWorkflowBuilder(workflowId) {
    if (!this.projectDataCurrent()) {
      return;
    }
    this.stopTaskPolling();
    this.taskExecutionView.close();
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
      this.state.openTaskId = null;
      this.byId('agentsV2ProjectsView').classList.add('hidden');
      this.byId('agentsV2Workspace').classList.add('hidden');
      this.byId('agentsV2Builder').classList.remove('hidden');
      this.byId('agentsV2TaskExecution').classList.add('hidden');
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
    this.byId('agentsV2TaskExecution').classList.add('hidden');
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
    this.renderProjectWorkspace();
    this.syncTaskPolling();
  }

  async openTaskExecution(taskId) {
    if (!this.state.selectedProjectId || !taskId) {
      return;
    }
    this.stopTaskPolling();
    this.workflowLoadSequence += 1;
    this.state.view = 'task';
    this.state.openTaskId = taskId;
    this.state.openWorkflowId = null;
    this.workflowBuilder.close();
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.add('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2TaskExecution').classList.remove('hidden');
    await this.taskExecutionView.open(taskId, this.currentProject(), this.state.repositories);
  }

  async closeTaskExecution() {
    const projectId = this.state.selectedProjectId;
    const loadSequence = this.projectLoadSequence;
    const page = this.state.tasksPage;
    const inFlightTasks = this.taskPollProjectId === projectId
      && this.taskPollLoadSequence === loadSequence
      && this.taskPollPage === page
      ? this.taskPollInFlight
      : null;
    this.taskExecutionView.close();
    this.state.view = 'project';
    this.state.openTaskId = null;
    this.byId('agentsV2TaskExecution').classList.add('hidden');
    this.byId('agentsV2Builder').classList.add('hidden');
    this.byId('agentsV2ProjectsView').classList.add('hidden');
    this.byId('agentsV2Workspace').classList.remove('hidden');
    this.renderProjectWorkspace();
    if (inFlightTasks) {
      await inFlightTasks;
    }
    if (this.disposed || this.state.view !== 'project' || !this.isCurrentProjectLoad(projectId, loadSequence)) {
      return;
    }
    if (projectId) {
      await this.loadTasks(projectId, loadSequence, { force: true, page });
    } else {
      this.syncTaskPolling();
    }
  }

  syncTaskPolling() {
    if (this.shouldPollTasks()) {
      this.scheduleTaskPolling();
      return;
    }
    this.stopTaskPolling();
  }

  scheduleTaskPolling() {
    if (this.disposed || this.taskPollTimer || this.taskPollInFlight) {
      return;
    }
    this.taskPollTimer = this.window.setTimeout(() => {
      this.taskPollTimer = null;
      this.pollTasks();
    }, this.taskPollIntervalMs);
  }

  async pollTasks() {
    if (this.disposed || !this.shouldPollTasks() || this.taskPollInFlight) {
      this.syncTaskPolling();
      return;
    }
    await this.loadTasks(this.state.selectedProjectId, this.projectLoadSequence, { background: true, page: this.state.tasksPage });
  }

  stopTaskPolling() {
    if (this.taskPollTimer) {
      this.window.clearTimeout(this.taskPollTimer);
      this.taskPollTimer = null;
    }
  }

  shouldPollTasks() {
    return !this.disposed
      && this.state.view === 'project'
      && this.tasksDataCurrent()
      && Array.isArray(this.state.tasks)
      && this.state.tasks.some((task) => ACTIVE_TASK_STATUSES.has(task.executionStatus));
  }

  parseOutputSchema() {
    this.showFieldError('');
    try {
      const parsed = JSON.parse(this.byId('agentsV2AgentOutputJson').value);
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        this.showFieldError('Output schema must be a JSON Schema object.');
        return null;
      }
      const schemaError = this.validateOutputSchema(parsed);
      if (schemaError) {
        this.showFieldError(schemaError);
        return null;
      }
      this.byId('agentsV2AgentOutputJson').value = JSON.stringify(parsed, null, 2);
      return parsed;
    } catch (_) {
      this.showFieldError('Output schema is not valid JSON.');
      return null;
    }
  }

  validateOutputSchema(schema) {
    if (typeof schema.type !== 'string' || !schema.type.trim()) {
      return 'Output schema must be JSON Schema with a top-level "type" key. Example: {"type":"object","properties":{"summ":{"type":"integer"}}}.';
    }
    if (!JSON_SCHEMA_TYPES.has(schema.type)) {
      return 'Output schema "type" must be one of: object, array, string, number, integer, boolean, null.';
    }
    if (schema.type === 'object') {
      if (schema.properties !== undefined && (!schema.properties || Array.isArray(schema.properties) || typeof schema.properties !== 'object')) {
        return 'Object output schema "properties" must be a JSON object.';
      }
      if (schema.required !== undefined && (!Array.isArray(schema.required) || schema.required.some((item) => typeof item !== 'string' || !item.trim()))) {
        return 'Object output schema "required" must be an array of property names.';
      }
      const properties = schema.properties || {};
      const invalidProperty = Object.entries(properties).find(([, value]) => !value || Array.isArray(value) || typeof value !== 'object');
      if (invalidProperty) {
        return `Property "${invalidProperty[0]}" must be a JSON Schema object, for example {"type":"string"}.`;
      }
    }
    if (schema.type === 'array' && schema.items !== undefined && (!schema.items || Array.isArray(schema.items) || typeof schema.items !== 'object')) {
      return 'Array output schema "items" must be a JSON Schema object.';
    }
    return '';
  }

  formatOutputSchemaJson() {
    this.parseOutputSchema();
  }

  applyOutputSchemaTemplate() {
    this.byId('agentsV2AgentOutputJson').value = JSON.stringify(DEFAULT_OUTPUT_SCHEMA, null, 2);
    this.showFieldError('');
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

  workflowsDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.workflowsProjectId === this.state.selectedProjectId;
  }

  tasksDataCurrent() {
    return Boolean(this.state.selectedProjectId)
      && this.state.tasksProjectId === this.state.selectedProjectId
      && this.state.tasksLoadedPage === this.state.tasksPage;
  }

  canCreateTask() {
    return Boolean(this.state.selectedProjectId)
      && this.workflowsDataCurrent()
      && this.tasksDataCurrent()
      && this.repositoriesDataCurrent()
      && !this.state.repositoriesLoadFailed
      && this.state.workflows.length > 0
      && this.state.repositories.length > 0;
  }

  isCurrentProjectLoad(projectId, loadSequence) {
    return this.state.selectedProjectId === projectId && this.projectLoadSequence === loadSequence;
  }

  isCurrentTaskLoad(projectId, loadSequence, page) {
    return this.isCurrentProjectLoad(projectId, loadSequence) && this.state.tasksPage === page;
  }

  resetTaskPage() {
    this.state.tasksPage = 0;
    this.state.tasksLoadedPage = null;
    this.state.tasksTotalItems = 0;
    this.state.tasksTotalPages = 0;
  }

  currentTaskPage() {
    return {
      page: this.state.tasksLoadedPage ?? this.state.tasksPage,
      size: this.state.tasksPageSize,
      totalItems: this.state.tasksTotalItems,
      totalPages: this.state.tasksTotalPages
    };
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
      openTaskExecution: (taskId) => this.openTaskExecution(taskId),
      closeTaskExecution: () => this.closeTaskExecution(),
      loadTasks: () => this.loadTasks(),
      goToTaskPage: (page) => this.goToTaskPage(page),
      deleteProject: (projectId) => this.deleteProject(projectId),
      deleteAgent: (agentId) => this.deleteAgent(agentId),
      deleteWorkflow: (workflowId) => this.deleteWorkflow(workflowId),
      deleteProjectTask: (taskId) => this.deleteProjectTask(taskId),
      openWorkflowBuilder: (workflowId) => this.openWorkflowBuilder(workflowId),
      addNode: (agentId) => this.workflowBuilder.addNode(agentId),
      removeConnection: (sourceNodeId, targetNodeId) => this.workflowBuilder.removeConnection(sourceNodeId, targetNodeId),
      removeNode: (nodeId) => this.workflowBuilder.removeNode(nodeId),
      saveWorkflow: () => this.workflowBuilder.save(),
      parseOutputSchema: () => this.parseOutputSchema(),
      state: this.state,
      taskExecutionView: this.taskExecutionView,
      workflowBuilder: this.workflowBuilder
    };
  }
}
