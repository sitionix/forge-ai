export const PROJECT_SECTIONS = [
  { key: 'resources', title: 'Resources', description: 'Git repositories and SSH resources', action: 'Add Resource', icon: '▤' },
  { key: 'agents', title: 'Agents', description: 'Reusable AI agents', action: 'New Agent', icon: '◇' },
  { key: 'workflows', title: 'Workflows', description: 'Workflow orchestration', action: 'New Workflow', icon: '⇄' },
  { key: 'tasks', title: 'Tasks', description: 'Tasks and execution history', action: 'New Task', icon: '☷' },
  { key: 'services', title: 'Services', description: 'Runtime services and status', action: 'New Service', icon: '⚙' },
  { key: 'ai-library', title: 'AI Library', description: 'Capabilities available to the active AI provider', icon: '▧' },
  { key: 'logs', title: 'Logs & Health', description: 'Logs, monitoring, diagnostics', icon: '⌁' }
];

export class ProjectDashboardView {
  constructor({ document, onNavigate, onCreate }) {
    this.root = document.getElementById('projectDashboard');
    this.root.addEventListener('click', (event) => {
      const link = event.target.closest('[data-project-section]');
      if (link && !event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {
        event.preventDefault(); onNavigate(link.dataset.projectSection);
      }
      const create = event.target.closest('[data-project-create]');
      if (create && !create.disabled) onCreate(create.dataset.projectCreate);
    });
  }

  render(projectId, counts, actions) {
    // Update in place: async loads must not replace focused navigation controls.
    if (this.root.dataset.projectId !== projectId) {
      this.root.dataset.projectId = projectId;
      this.root.innerHTML = `<dl class="project-kpis" aria-label="Project counts" aria-live="polite">${PROJECT_SECTIONS.filter((s) => s.action).map((s) => `<div><dt>${s.title}</dt><dd data-kpi="${s.key}"></dd></div>`).join('')}</dl>
        <nav class="project-dashboard-grid" aria-label="Project sections">${PROJECT_SECTIONS.map((s) => `
          <article class="panel project-navigation-card">
            <span class="project-navigation-icon" aria-hidden="true">${s.icon}</span>
            <h2>${s.title}</h2><p>${s.description}</p>
            ${s.key !== 'logs' ? `<span class="muted" data-card-count="${s.key}"></span>` : ''}
            <div class="project-navigation-actions">
              <a ${s.key === 'logs' ? 'id="projectLogsOpen"' : ''} class="button small secondary" data-project-section="${s.key}" href="#/projects/${encodeURIComponent(projectId)}/${s.key}">Open ${s.title}</a>
              ${s.action ? `<button class="button small project-quick-create" type="button" data-project-create="${s.key}" aria-label="${s.action}" title="${s.action}">+</button>` : ''}
            </div>
            ${s.action ? `<span class="project-action-hint muted" data-action-hint="${s.key}"></span>` : ''}
          </article>`).join('')}</nav>`;
    }
    for (const section of PROJECT_SECTIONS.filter((s) => s.key !== 'logs')) {
      const count = counts[section.key];
      const kpi = this.root.querySelector(`[data-kpi="${section.key}"]`);
      if (kpi) kpi.textContent = count;
      this.root.querySelector(`[data-card-count="${section.key}"]`).textContent = typeof count === 'number' ? `${count} ${count === 1 ? 'item' : 'items'}` : count;
      const button = this.root.querySelector(`[data-project-create="${section.key}"]`);
      const reason = actions[section.key] || '';
      if (!button) continue;
      button.disabled = Boolean(reason);
      button.title = reason || section.action;
      const hint = this.root.querySelector(`[data-action-hint="${section.key}"]`);
      hint.id = `projectCreateHint-${section.key}`;
      hint.textContent = reason;
      button.setAttribute('aria-describedby', hint.id);
    }
  }
}
