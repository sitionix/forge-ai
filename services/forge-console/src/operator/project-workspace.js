import { escapeHtml } from './dom-render-helpers.js';

export class ProjectWorkspace {
  constructor(options) {
    this.document = options.document;
    this.onBack = options.onBack;
    this.onOpenLogs = options.onOpenLogs;
    this.onAddService = options.onAddService || (()=>{});
    this.onOpenRepository = options.onOpenRepository || (() => {});
    this.onOpenService = options.onOpenService || (()=>{});
    this.onEditService = options.onEditService || (() => {});
    this.onDeleteService = options.onDeleteService || (() => {});
    this.onNewAgent = options.onNewAgent;
    this.onImportRepository = options.onImportRepository;
    this.onCloneRepository = options.onCloneRepository || (() => {});
    this.onRefreshRepository = options.onRefreshRepository || (() => {});
    this.onPullRepository = options.onPullRepository || (() => {});
    this.onEditAgent = options.onEditAgent;
    this.onNewWorkflow = options.onNewWorkflow;
    this.onOpenWorkflow = options.onOpenWorkflow;
    this.onDeleteAgent = options.onDeleteAgent;
    this.onDeleteWorkflow = options.onDeleteWorkflow;
    this.onNewTask = options.onNewTask;
    this.onOpenTask = options.onOpenTask;
    this.onDeleteTask = options.onDeleteTask;
    this.onTaskPage = options.onTaskPage;
  }

  bind() {
    this.byId('agentsV2WorkspaceBack')?.addEventListener('click', () => this.onBack());
    this.byId('projectLogsOpen')?.addEventListener('click', () => this.onOpenLogs());
    this.byId('projectServiceAdd')?.addEventListener('click', () => this.onAddService());
    this.byId('agentsV2ImportRepository')?.addEventListener('click', () => this.onImportRepository());
    this.byId('agentsV2CreateAgent')?.addEventListener('click', () => this.onNewAgent());
    this.byId('agentsV2CreateWorkflow')?.addEventListener('click', () => this.onNewWorkflow());
    this.byId('agentsV2CreateTask')?.addEventListener('click', () => this.onNewTask());
  }

  render(project, repositories, services, agents, workflows, tasks, repositoriesCurrent, dataCurrent, workflowsCurrent, tasksCurrent, repositoriesLoadFailed, tasksLoadFailed, runtimeCatalog = null, taskPage = null) {
    this.byId('agentsV2ProjectTitle').textContent = project ? project.name : 'Project';
    this.byId('agentsV2ProjectCrumbs').textContent = project ? `Projects / ${project.name}` : 'Projects';
    this.byId('agentsV2ImportRepository').disabled = !project || !repositoriesCurrent;
    this.byId('agentsV2CreateAgent').disabled = !dataCurrent;
    this.byId('agentsV2CreateWorkflow').disabled = !dataCurrent;
    this.byId('agentsV2CreateTask').disabled = !project
      || !repositoriesCurrent
      || repositoriesLoadFailed
      || !workflowsCurrent
      || !tasksCurrent
      || !workflows.length
      || !repositories.length;
    this.renderRepositories(repositories, services, repositoriesCurrent, repositoriesLoadFailed);
    this.renderStandaloneServices(services);
    this.renderAgents(agents, runtimeCatalog);
    this.renderWorkflows(workflows);
    this.renderTasks(tasks, workflowsCurrent, workflows.length > 0, tasksCurrent, tasksLoadFailed, taskPage);
  }

  renderLoading() {
    this.byId('agentsV2CreateAgent').disabled = true;
    this.byId('agentsV2ImportRepository').disabled = true;
    this.byId('agentsV2CreateWorkflow').disabled = true;
    this.byId('agentsV2CreateTask').disabled = true;
    this.byId('agentsV2RepositoriesList').innerHTML = '<div class="muted-state">Loading repositories...</div>';
    this.byId('projectStandaloneServicesSection')?.classList.add('hidden');
    this.byId('projectStandaloneServicesList').innerHTML = '';
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">Loading workflows...</div>';
    this.byId('agentsV2TasksList').innerHTML = '<div class="muted-state">Loading tasks...</div>';
  }

  renderStandaloneServices(services) {
    const section = this.byId('projectStandaloneServicesSection');
    const list = this.byId('projectStandaloneServicesList');
    const standalone = services
      .filter((service) => !service.repositoryId)
      .sort((left, right) => (left.name || '').localeCompare(right.name || '') || (left.id || '').localeCompare(right.id || ''));
    section?.classList.toggle('hidden', !standalone.length);
    if (!standalone.length) {
      list.innerHTML = '';
      return;
    }
    list.innerHTML = standalone.map((service) => `
      <button class="standalone-service-row" type="button" data-standalone-service-id="${escapeHtml(service.id)}">
        <span class="repository-main">
          <strong>${escapeHtml(service.name)}</strong>
          <span class="repository-git-state">No repository</span>
        </span>
        <span class="repository-runtime-summary repository-runtime-${escapeHtml(runtimeTone(service.runtimeStatus || 'UNKNOWN'))}">
          <span class="repository-runtime-dot" aria-hidden="true"></span><span data-service-runtime-status="${escapeHtml(service.id)}">${escapeHtml(service.runtimeStatus || 'UNKNOWN')}</span>
        </span>
      </button>
    `).join('');
    list.querySelectorAll('[data-standalone-service-id]').forEach((element) =>
      element.addEventListener('click', () => this.onOpenService(element.dataset.standaloneServiceId)));
  }

  updateServiceRuntimeStatus(serviceId, status) {
    const list = this.document;
    const statusElement = [...(list?.querySelectorAll('[data-service-runtime-status]') || [])]
      .find((element) => element.dataset.serviceRuntimeStatus === serviceId);
    if (statusElement) statusElement.textContent = status || 'UNKNOWN';
  }

  renderRepositories(repositories, services, repositoriesCurrent, repositoriesLoadFailed) {
    const list = this.byId('agentsV2RepositoriesList');
    if (repositoriesLoadFailed) {
      list.innerHTML = '';
      return;
    }
    if (!repositoriesCurrent) {
      list.innerHTML = '<div class="muted-state">Loading repositories...</div>';
      return;
    }
    if (!repositories.length) {
      list.innerHTML = '<div class="muted-state">No repositories yet.</div>';
      return;
    }
    list.innerHTML = repositories.map((repository) => `
      <article class="repository-row repository-navigation-row" role="button" tabindex="0" data-repository-id="${escapeHtml(repository.id)}">
        <span class="repository-main">
          <code>${escapeHtml(repository.name || '')}</code>
          ${this.renderRepositoryGitState(repository)}
        </span>
        <span class="repository-runtime-summary repository-runtime-${escapeHtml(runtimeTone(this.repositoryRuntimeSummary(repository, services)))}">
          <span class="repository-runtime-dot" aria-hidden="true"></span><span data-repository-runtime-status="${escapeHtml(repository.id)}">${escapeHtml(this.repositoryRuntimeSummary(repository, services))}</span>
        </span>
      </article>
    `).join('');
    list.querySelectorAll('[data-repository-id]').forEach((element) => {
      const open = () => this.onOpenRepository(element.dataset.repositoryId);
      element.addEventListener('click', open);
      element.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' && event.key !== ' ') {
          return;
        }
        event.preventDefault();
        open();
      });
    });
  }

  repositoryRuntimeSummary(repository, services) {
    const linked = services.filter((service) => service.repositoryId === repository.id);
    if (!linked.length) {
      return 'NOT CONFIGURED';
    }
    const statuses = linked.map((service) => service.runtimeStatus || 'UNKNOWN');
    if (linked.length === 1) {
      return statuses[0];
    }
    if (statuses.includes('FAILED')) {
      return 'FAILED';
    }
    if (statuses.includes('RUNNING')) {
      return 'RUNNING';
    }
    if (statuses.every((status) => status === 'STOPPED')) {
      return 'STOPPED';
    }
    return 'UNKNOWN';
  }

  renderRepositoryAction(repository, pullingRepositoryIds = new Set()) {
    const git = repository.git;
    if (!repository.cloned || !git?.workingTree) {
      return '';
    }
    const disabled = !git.pullAvailable || pullingRepositoryIds.has(repository.id);
    return `<button class="button tiny secondary" type="button" data-pull-repository-id="${escapeHtml(repository.id)}"${disabled ? ' disabled' : ''}>Pull</button>`;
  }

  renderRepositoryGitState(repository) {
    const git = repository.git;
    if (!repository.cloned) {
      return '';
    }
    if (!git || !git.workingTree) {
      return '<span class="repository-git-state repository-git-state-invalid">Invalid Git checkout</span>';
    }
    const headLabel = git.branch || 'detached';
    const workingTree = git.workingTree === 'DIRTY' ? 'Dirty' : 'Clean';
    const tone = git.workingTree === 'DIRTY' ? 'dirty' : 'clean';
    return `
      <span class="repository-git-state">
        ${escapeHtml(headLabel)} · <span class="repository-git-state-${tone}">${escapeHtml(workingTree)}</span>
      </span>
    `;
  }

  renderAgents(agents, runtimeCatalog = null) {
    const list = this.byId('agentsV2AgentsList');
    if (!agents.length) {
      list.innerHTML = '<div class="muted-state">No agents yet.</div>';
      return;
    }
    list.innerHTML = agents.map((agent) => `
      <article class="agents-v2-card agents-v2-deletable">
        <button
          class="entity-delete-control"
          type="button"
          data-delete-agent-id="${escapeHtml(agent.id)}"
          aria-label="Delete agent ${escapeHtml(agent.name)}"
          title="Delete agent"
        >×</button>
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(agent.instructions || 'Reusable agent definition')}</p>
        ${this.renderAgentModelMeta(agent.model, runtimeCatalog)}
        <div class="agents-v2-card-actions">
          <button class="button small secondary" type="button" data-agent-id="${escapeHtml(agent.id)}">Edit</button>
        </div>
      </article>
    `).join('');
    list.querySelectorAll('[data-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.onEditAgent(element.dataset.agentId));
    });
    list.querySelectorAll('[data-delete-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.onDeleteAgent(element.dataset.deleteAgentId));
    });
  }

  renderAgentModelMeta(modelSelection, runtimeCatalog = null) {
    if (!modelSelection?.providerId || !modelSelection?.modelId) {
      return '<div class="agents-v2-model-meta muted">No model selected</div>';
    }
    const resolved = this.resolveRuntimeModel(modelSelection, runtimeCatalog);
    const providerLabel = resolved.provider?.displayName || modelSelection.providerId;
    const modelLabel = resolved.model?.displayName || modelSelection.modelId;
    const effortId = modelSelection.effortId || 'No effort';
    const tone = effortTone(modelSelection.effortId);
    return `
      <div class="agents-v2-model-meta">
        <span>${escapeHtml(providerLabel)} · ${escapeHtml(modelLabel)}</span>
        <span class="agents-v2-effort agents-v2-effort-${escapeHtml(tone)}" data-effort-tone="${escapeHtml(tone)}">
          <span class="agents-v2-effort-dot" aria-hidden="true"></span>
          ${escapeHtml(effortId)}
        </span>
      </div>
    `;
  }

  resolveRuntimeModel(modelSelection, runtimeCatalog = null) {
    const providers = runtimeCatalog?.providers || [];
    const provider = providers.find((candidate) => candidate.providerId === modelSelection.providerId);
    const model = provider?.models?.find((candidate) => candidate.modelId === modelSelection.modelId);
    return { provider, model };
  }

  renderWorkflows(workflows) {
    const list = this.byId('agentsV2WorkflowsList');
    if (!workflows.length) {
      list.innerHTML = '<div class="muted-state">No workflows yet.</div>';
      return;
    }
    list.innerHTML = workflows.map((workflow) => `
      <article class="agents-v2-card agents-v2-deletable">
        <button
          class="entity-delete-control"
          type="button"
          data-delete-workflow-id="${escapeHtml(workflow.id)}"
          aria-label="Delete workflow ${escapeHtml(workflow.name)}"
          title="Delete workflow"
        >×</button>
        <h3>${escapeHtml(workflow.name)}</h3>
        <p>${(workflow.nodes || []).length} nodes</p>
        <div class="agents-v2-card-actions">
          <button class="button small secondary" type="button" data-workflow-id="${escapeHtml(workflow.id)}">Open</button>
        </div>
      </article>
    `).join('');
    list.querySelectorAll('[data-workflow-id]').forEach((element) => {
      element.addEventListener('click', () => this.onOpenWorkflow(element.dataset.workflowId));
    });
    list.querySelectorAll('[data-delete-workflow-id]').forEach((element) => {
      element.addEventListener('click', () => this.onDeleteWorkflow(element.dataset.deleteWorkflowId));
    });
  }

  renderTasks(tasks, workflowsCurrent, hasWorkflows, tasksCurrent, tasksLoadFailed, taskPage = null) {
    const list = this.byId('agentsV2TasksList');
    if (tasksLoadFailed) {
      list.innerHTML = '';
      return;
    }
    if (!tasksCurrent) {
      list.innerHTML = '<div class="muted-state">Loading tasks...</div>';
      return;
    }
    if (tasks.length) {
      list.innerHTML = `
        <div class="agents-v2-task-table" role="table" aria-label="Tasks">
          <div class="agents-v2-task-row agents-v2-task-row-head" role="row">
            <span>Title</span>
            <span>Workflow</span>
            <span>Status</span>
            <span>Created</span>
            <span>Actions</span>
          </div>
          ${tasks.map((task) => `
            <div class="agents-v2-task-row" role="row" data-task-row-id="${escapeHtml(task.id)}">
              <strong>${escapeHtml(task.title)}</strong>
              <span>${escapeHtml(task.workflowName || 'Unknown workflow')}</span>
              <span class="agents-v2-status agents-v2-status-${escapeHtml(statusTone(task.executionStatus))}" data-task-status="${escapeHtml(task.executionStatus || 'UNKNOWN')}">
                ${escapeHtml(task.executionStatus || 'UNKNOWN')}
              </span>
              <span>${escapeHtml(this.formatDate(task.createdAt))}</span>
              <span class="agents-v2-task-actions">
                <button class="button tiny secondary" type="button" data-task-id="${escapeHtml(task.id)}">Open</button>
                <button
                  class="entity-delete-control"
                  type="button"
                  data-delete-task-id="${escapeHtml(task.id)}"
                  aria-label="Delete task ${escapeHtml(task.title)}"
                  title="Delete task"
                >×</button>
              </span>
            </div>
          `).join('')}
        </div>
        ${this.renderTaskPagination(taskPage)}
      `;
      list.querySelectorAll('[data-task-id]').forEach((element) => {
        element.addEventListener('click', () => this.onOpenTask(element.dataset.taskId));
      });
      list.querySelectorAll('[data-delete-task-id]').forEach((element) => {
        element.addEventListener('click', () => this.onDeleteTask(element.dataset.deleteTaskId));
      });
      this.bindTaskPagination(list, taskPage);
      return;
    }
    if (workflowsCurrent && !hasWorkflows) {
      list.innerHTML = '<div class="muted-state">Create a workflow before creating a task.</div>';
      return;
    }
    list.innerHTML = '<div class="muted-state">No tasks yet.</div>';
  }

  renderTaskPagination(taskPage) {
    if (!taskPage || taskPage.totalPages <= 1) {
      return '';
    }
    const page = Number(taskPage.page) || 0;
    const totalPages = Number(taskPage.totalPages) || 1;
    return `
      <div class="agents-v2-pagination">
        <button class="button tiny secondary" type="button" data-task-page="prev" ${page <= 0 ? 'disabled' : ''}>Previous</button>
        <span>Page ${escapeHtml(String(page + 1))} of ${escapeHtml(String(totalPages))}</span>
        <button class="button tiny secondary" type="button" data-task-page="next" ${page >= totalPages - 1 ? 'disabled' : ''}>Next</button>
      </div>
    `;
  }

  bindTaskPagination(list, taskPage) {
    if (!taskPage || taskPage.totalPages <= 1) {
      return;
    }
    const page = Number(taskPage.page) || 0;
    list.querySelector('[data-task-page="prev"]')?.addEventListener('click', () => this.onTaskPage(page - 1));
    list.querySelector('[data-task-page="next"]')?.addEventListener('click', () => this.onTaskPage(page + 1));
  }

  formatDate(value) {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString();
  }

  byId(id) {
    return this.document.getElementById(id);
  }
}

export function effortTone(effortId) {
  const normalized = (effortId || '').toLowerCase();
  if (normalized === 'minimal' || normalized === 'low') {
    return 'low';
  }
  if (normalized === 'medium') {
    return 'medium';
  }
  if (normalized === 'high') {
    return 'high';
  }
  if (normalized === 'xhigh') {
    return 'maximum';
  }
  return 'neutral';
}

export function statusTone(status) {
  const normalized = (status || '').toLowerCase();
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
  if (normalized === 'stopped') {
    return 'stopped';
  }
  if (normalized === 'not configured') {
    return 'not-configured';
  }
  if (normalized === 'blocked') {
    return 'blocked';
  }
  if (normalized === 'pending') {
    return 'pending';
  }
  if (normalized === 'cancelled') {
    return 'cancelled';
  }
  return 'unknown';
}

export function runtimeTone(status) {
  const normalized = (status || '').toLowerCase().replace(/\s+/g, '-');
  if (normalized === 'running') {
    return 'running';
  }
  if (normalized === 'stopped' || normalized === 'not-configured') {
    return 'stopped';
  }
  if (normalized === 'failed') {
    return 'failed';
  }
  return 'unknown';
}
