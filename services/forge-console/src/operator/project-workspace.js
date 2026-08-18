import { escapeHtml } from './dom-render-helpers.js';

export class ProjectWorkspace {
  constructor(options) {
    this.document = options.document;
    this.onBack = options.onBack;
    this.onNewAgent = options.onNewAgent;
    this.onImportRepository = options.onImportRepository;
    this.onCloneRepository = options.onCloneRepository || (() => {});
    this.onCheckRepositoryUpdates = options.onCheckRepositoryUpdates || (() => {});
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
    this.byId('agentsV2ImportRepository')?.addEventListener('click', () => this.onImportRepository());
    this.byId('agentsV2CreateAgent')?.addEventListener('click', () => this.onNewAgent());
    this.byId('agentsV2CreateWorkflow')?.addEventListener('click', () => this.onNewWorkflow());
    this.byId('agentsV2CreateTask')?.addEventListener('click', () => this.onNewTask());
  }

  render(project, repositories, agents, workflows, tasks, repositoriesCurrent, dataCurrent, workflowsCurrent, tasksCurrent, repositoriesLoadFailed, tasksLoadFailed, runtimeCatalog = null, taskPage = null, cloningRepositoryIds = new Set(), checkingRepositoryIds = new Set(), pullingRepositoryIds = new Set()) {
    this.byId('agentsV2ProjectTitle').textContent = project ? project.name : 'Project';
    this.byId('agentsV2ProjectCrumbs').textContent = project ? `Projects / ${project.name}` : 'Projects';
    this.byId('agentsV2ImportRepository').disabled = !project || !repositoriesCurrent;
    this.byId('agentsV2CreateAgent').disabled = !dataCurrent;
    this.byId('agentsV2CreateWorkflow').disabled = !dataCurrent;
    this.byId('agentsV2CreateTask').disabled = !project || !workflowsCurrent || !tasksCurrent || !workflows.length;
    this.renderRepositories(repositories, repositoriesCurrent, repositoriesLoadFailed, cloningRepositoryIds, checkingRepositoryIds, pullingRepositoryIds);
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
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">Loading workflows...</div>';
    this.byId('agentsV2TasksList').innerHTML = '<div class="muted-state">Loading tasks...</div>';
  }

  renderRepositories(repositories, repositoriesCurrent, repositoriesLoadFailed, cloningRepositoryIds = new Set(), checkingRepositoryIds = new Set(), pullingRepositoryIds = new Set()) {
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
      <article class="repository-row">
        <span class="repository-main">
          <code>${escapeHtml(repository.name || '')}</code>
          ${this.renderRepositoryGitState(repository)}
        </span>
        ${repository.cloned === false ? `
          <button class="button tiny secondary" type="button" data-clone-repository-id="${escapeHtml(repository.id)}"${cloningRepositoryIds.has(repository.id) ? ' disabled' : ''}>Clone</button>
        ` : ''}
        ${this.renderRepositoryAction(repository, checkingRepositoryIds, pullingRepositoryIds)}
      </article>
    `).join('');
    list.querySelectorAll('[data-clone-repository-id]').forEach((element) => {
      element.addEventListener('click', () => this.onCloneRepository(element.dataset.cloneRepositoryId));
    });
    list.querySelectorAll('[data-pull-repository-id]').forEach((element) => {
      element.addEventListener('click', () => this.onPullRepository(element.dataset.pullRepositoryId));
    });
    list.querySelectorAll('[data-check-repository-id]').forEach((element) => {
      element.addEventListener('click', () => this.onCheckRepositoryUpdates(element.dataset.checkRepositoryId));
    });
  }

  renderRepositoryAction(repository, checkingRepositoryIds = new Set(), pullingRepositoryIds = new Set()) {
    const gitState = repository.gitState;
    if (repository.cloned === false || !gitState || gitState.valid === false) {
      return '';
    }
    const relation = gitState.upstream?.relation || null;
    if (relation === 'BEHIND') {
      const disabled = !gitState.pullAllowed || pullingRepositoryIds.has(repository.id);
      return `<button class="button tiny secondary" type="button" data-pull-repository-id="${escapeHtml(repository.id)}"${disabled ? ' disabled' : ''}>Pull</button>`;
    }
    if (relation === 'UP_TO_DATE' || relation === 'MISSING') {
      if (!gitState.checkUpdatesAllowed && !checkingRepositoryIds.has(repository.id)) {
        return '';
      }
      const disabled = !gitState.checkUpdatesAllowed || checkingRepositoryIds.has(repository.id);
      return `<button class="button tiny secondary" type="button" data-check-repository-id="${escapeHtml(repository.id)}"${disabled ? ' disabled' : ''}>Check</button>`;
    }
    return '';
  }

  renderRepositoryGitState(repository) {
    if (repository.cloned === false) {
      return '';
    }
    const gitState = repository.gitState;
    if (!gitState || gitState.valid === false) {
      return '<span class="repository-git-state repository-git-state-invalid">Invalid Git checkout</span>';
    }
    const headLabel = this.repositoryHeadLabel(gitState.head);
    const workingTree = gitState.workingTree === 'DIRTY' ? 'Dirty' : 'Clean';
    const conflicted = gitState.conflictState === 'CONFLICTED';
    const stateLabel = conflicted ? 'Conflicted' : workingTree;
    const tone = conflicted ? 'dirty' : (gitState.workingTree === 'DIRTY' ? 'dirty' : 'clean');
    const upstreamLabel = gitState.upstream?.relation === 'UP_TO_DATE' ? ' · Up to date' : '';
    return `
      <span class="repository-git-state">
        ${escapeHtml(headLabel)} · <span class="repository-git-state-${tone}">${escapeHtml(stateLabel)}</span>${escapeHtml(upstreamLabel)}
      </span>
    `;
  }

  repositoryHeadLabel(head) {
    if (!head) {
      return 'Unknown HEAD';
    }
    if (head.type === 'DETACHED') {
      return `detached@${this.shortCommit(head.commit)}`;
    }
    return head.ref || 'Unborn branch';
  }

  shortCommit(commit) {
    return commit ? String(commit).slice(0, 7) : 'unknown';
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
