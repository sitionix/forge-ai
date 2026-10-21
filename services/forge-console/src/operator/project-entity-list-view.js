import { escapeHtml } from './dom-render-helpers.js';

const LIST_IDS = { resources: 'agentsV2RepositoriesList', agents: 'agentsV2AgentsList', workflows: 'agentsV2WorkflowsList', tasks: 'agentsV2TasksList', services: 'projectServicesList' };

export class ProjectEntityListView {
  constructor({ document, workspace, onSearch, onOpenService, onEditService, onDeleteService }) {
    this.document = document;
    this.workspace = workspace;
    this.search = document.getElementById('projectEntitySearch');
    this.search.addEventListener('input', () => onSearch());
    const services = document.getElementById('projectServicesList');
    services.addEventListener('click', (event) => {
      const button = event.target.closest('[data-service-action]');
      if (!button) return;
      const handlers = { open: onOpenService, edit: onEditService, delete: onDeleteService };
      handlers[button.dataset.serviceAction](button.dataset.serviceId);
    });
  }

  render(section, state, counts, flags) {
    const searching = ['resources', 'agents', 'workflows', 'services'].includes(section);
    if (this.section !== section || this.projectId !== state.selectedProjectId) this.search.value = '';
    this.section = section;
    this.projectId = state.selectedProjectId;
    this.document.getElementById('projectEntityToolbar').classList.toggle('hidden', !searching);
    this.search.setAttribute('aria-label', `Search ${section}`);
    this.document.getElementById('projectEntitySearchLabel').textContent = section;
    this.document.querySelectorAll('[data-entity-section]').forEach((element) => element.classList.toggle('hidden', element.dataset.entitySection !== section));
    for (const [key, id] of Object.entries(LIST_IDS)) {
      if (key !== section) this.document.getElementById(id).replaceChildren();
    }
    if (!LIST_IDS[section]) return;
    const list = this.document.getElementById(LIST_IDS[section]);
    if (section === 'resources' && typeof counts.resources !== 'number') {
      const states = state.collectionStates;
      const repositories = states.repositories === 'CURRENT' ? state.repositories : [];
      const assets = states.assets === 'CURRENT' ? state.assets : [];
      const query = this.search.value.trim().toLocaleLowerCase();
      const matches = (items) => items.filter((item) => (item.name || '').toLocaleLowerCase().includes(query));
      this.workspace.renderRepositories(matches(repositories), matches(assets), true, false);
      if (!repositories.length && !assets.length) list.replaceChildren();
      const message = this.document.createElement('div');
      message.className = 'muted-state';
      message.textContent = `${counts.resources}. Some resources may be missing.`;
      list.append(message);
      return;
    }
    if (typeof counts[section] !== 'number') {
      list.innerHTML = `<div class="muted-state" role="status">${escapeHtml(counts[section])}</div>`;
      return;
    }
    const query = this.search.value.trim().toLocaleLowerCase();
    const filter = (items) => items.filter((item) => (item.name || '').toLocaleLowerCase().includes(query));
    if (section === 'resources') this.workspace.renderRepositories(filter(state.repositories), filter(state.assets), true, false);
    if (section === 'agents') this.workspace.renderAgents(filter(state.agents), state.runtime);
    if (section === 'workflows') this.workspace.renderWorkflows(filter(state.workflows));
    if (section === 'tasks') this.workspace.renderTasks(state.tasks, flags.workflowsCurrent, state.workflows.length > 0, flags.tasksCurrent, state.tasksLoadFailed, flags.taskPage);
    if (section === 'services') this.renderServices(filter(state.services), state.repositories);
    if (query && !list.querySelector('article')) list.innerHTML = '<div class="muted-state">No matching items. Try another search.</div>';
  }

  renderServices(services, repositories) {
    this.document.getElementById('projectServicesList').innerHTML = services.length ? services.map((service) => {
      const repository = repositories.find((item) => item.id === service.repositoryId);
      const target = service.runtimeTarget || {};
      const configuration = [target.connection, target.provider, target.container || target.unit].filter(Boolean).join(' · ');
      return `<article class="agents-v2-card"><h3>${escapeHtml(service.name)}</h3>
        <p>${escapeHtml(repository?.name || (service.repositoryId ? 'Repository unavailable' : 'No repository'))}</p>
        <p>${escapeHtml(configuration || 'Runtime not configured')}</p>
        <p class="muted">Open for current runtime status</p>
        <div class="agents-v2-card-actions">${['open', 'edit', 'delete'].map((action) => `<button class="button small secondary" type="button" data-service-action="${action}" data-service-id="${escapeHtml(service.id)}" aria-label="${action} service ${escapeHtml(service.name)}">${action[0].toUpperCase() + action.slice(1)}</button>`).join('')}</div></article>`;
    }).join('') : '<div class="muted-state">No services yet. Create a service to configure its runtime.</div>';
  }
}
