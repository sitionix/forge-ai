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

  render(project, agents, workflows, dataCurrent) {
    this.byId('agentsV2ProjectTitle').textContent = project ? project.name : 'Project';
    this.byId('agentsV2ProjectCrumbs').textContent = project ? `Projects / ${project.name}` : 'Projects';
    this.byId('agentsV2CreateAgent').disabled = !dataCurrent;
    this.byId('agentsV2CreateWorkflow').disabled = !dataCurrent;
    this.renderAgents(agents);
    this.renderWorkflows(workflows);
  }

  renderLoading() {
    this.byId('agentsV2CreateAgent').disabled = true;
    this.byId('agentsV2CreateWorkflow').disabled = true;
    this.byId('agentsV2AgentsList').innerHTML = '<div class="muted-state">Loading agents...</div>';
    this.byId('agentsV2WorkflowsList').innerHTML = '<div class="muted-state">Loading workflows...</div>';
  }

  renderAgents(agents) {
    const list = this.byId('agentsV2AgentsList');
    if (!agents.length) {
      list.innerHTML = '<div class="muted-state">No agents yet.</div>';
      return;
    }
    list.innerHTML = agents.map((agent) => `
      <article class="agents-v2-card">
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(agent.instructions || 'Reusable agent definition')}</p>
        <button class="button small secondary" type="button" data-agent-id="${escapeHtml(agent.id)}">Edit</button>
      </article>
    `).join('');
    list.querySelectorAll('[data-agent-id]').forEach((element) => {
      element.addEventListener('click', () => this.onEditAgent(element.dataset.agentId));
    });
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
