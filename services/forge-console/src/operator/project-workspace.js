import { escapeHtml } from './dom-render-helpers.js';

export class ProjectWorkspace {
  constructor(options) {
    this.document = options.document;
    this.onBack = options.onBack;
    this.onNewAgent = options.onNewAgent;
    this.onEditAgent = options.onEditAgent;
    this.onNewWorkflow = options.onNewWorkflow;
    this.onOpenWorkflow = options.onOpenWorkflow;
  }

  bind() {
    this.byId('agentsV2WorkspaceBack')?.addEventListener('click', () => this.onBack());
    this.byId('agentsV2CreateAgent')?.addEventListener('click', () => this.onNewAgent());
    this.byId('agentsV2CreateWorkflow')?.addEventListener('click', () => this.onNewWorkflow());
  }

  render(project, agents, workflows, dataCurrent, runtimeCatalog = null) {
    this.byId('agentsV2ProjectTitle').textContent = project ? project.name : 'Project';
    this.byId('agentsV2ProjectCrumbs').textContent = project ? `Projects / ${project.name}` : 'Projects';
    this.byId('agentsV2CreateAgent').disabled = !dataCurrent;
    this.byId('agentsV2CreateWorkflow').disabled = !dataCurrent;
    this.renderAgents(agents, runtimeCatalog);
    this.renderWorkflows(workflows);
  }

  renderLoading() {
    this.byId('agentsV2CreateAgent').disabled = true;
    this.byId('agentsV2CreateWorkflow').disabled = true;
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">Loading workflows...</div>';
  }

  renderAgents(agents, runtimeCatalog = null) {
    const list = this.byId('agentsV2AgentsList');
    if (!agents.length) {
      list.innerHTML = '<div class="muted-state">No agents yet.</div>';
      return;
    }
    list.innerHTML = agents.map((agent) => `
      <article class="agents-v2-card">
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(agent.instructions || 'Reusable agent definition')}</p>
        ${this.renderAgentModelMeta(agent.model, runtimeCatalog)}
        <button class="button small secondary" type="button" data-agent-id="${escapeHtml(agent.id)}">Edit</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.onEditAgent(element.dataset.agentId));
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
      <article class="agents-v2-card">
        <h3>${escapeHtml(workflow.name)}</h3>
        <p>${(workflow.nodes || []).length} nodes</p>
        <button class="button small secondary" type="button" data-workflow-id="${escapeHtml(workflow.id)}">Open</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-workflow-id]').forEach((element) => {
      element.addEventListener('click', () => this.onOpenWorkflow(element.dataset.workflowId));
    });
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
