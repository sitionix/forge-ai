import { escapeHtml, pill, renderRequestError, setError } from './dom-render-helpers.js';
import { RequestCoordinator } from './request-coordinator.js';

const EMPTY_DRAFT = Object.freeze({
  providerId: null,
  modelId: null,
  settings: {}
});

export class AiRuntimeView {
  constructor(options) {
    this.document = options.document || document;
    this.http = options.http;
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.disposed = false;
    this.jarvisStatus = null;
    this.runtime = null;
    this.runtimeError = null;
    this.runtimeLoading = false;
    this.draft = this.emptyDraft();
    this.editButton = null;
    this.dialog = null;
    this.openListener = () => this.openDialog();
    this.closeListener = () => this.closeDialog();
    this.dialogCloseListener = () => this.handleDialogClosed();
    this.dialogClickListener = (event) => this.handleDialogClick(event);
    this.dialogKeydownListener = (event) => {
      if (event.key === 'Escape') {
        this.closeDialog();
      }
    };
  }

  mount() {
    this.disposed = false;
    this.editButton = this.document.getElementById('editAiRuntime');
    this.dialog = this.document.getElementById('aiRuntimeDialog');
    this.editButton?.addEventListener('click', this.openListener);
    this.document.getElementById('closeAiRuntimeDialog')?.addEventListener('click', this.closeListener);
    this.document.getElementById('cancelAiRuntimeDialog')?.addEventListener('click', this.closeListener);
    this.dialog?.addEventListener('close', this.dialogCloseListener);
    this.dialog?.addEventListener('click', this.dialogClickListener);
    this.dialog?.addEventListener('keydown', this.dialogKeydownListener);
    this.renderRuntimePanel();
    this.renderModal();
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.editButton?.removeEventListener('click', this.openListener);
    this.document.getElementById('closeAiRuntimeDialog')?.removeEventListener('click', this.closeListener);
    this.document.getElementById('cancelAiRuntimeDialog')?.removeEventListener('click', this.closeListener);
    this.dialog?.removeEventListener('close', this.dialogCloseListener);
    this.dialog?.removeEventListener('click', this.dialogClickListener);
    this.dialog?.removeEventListener('keydown', this.dialogKeydownListener);
    this.editButton = null;
    this.dialog = null;
  }

  setJarvisStatus(status) {
    this.jarvisStatus = status || null;
    this.renderRuntimePanel();
  }

  async loadRuntime() {
    this.runtimeLoading = true;
    this.renderRuntimePanel();
    this.renderModal();
    try {
      const result = await this.requestCoordinator.run('ai-runtime-options', ({ signal }) => this.http.get('/knowledge/ai-runtime', { signal }));
      if (!result.applied || this.disposed) {
        return null;
      }
      this.runtime = normalizeRuntime(result.value);
      this.runtimeError = null;
      setError('aiRuntimeError', null, this.document);
      setError('aiRuntimeModalError', null, this.document);
      return this.runtime;
    } catch (error) {
      if (!this.disposed) {
        this.runtimeError = error;
        renderRequestError('aiRuntimeError', error, {
          endpoint: '/knowledge/ai-runtime',
          title: 'AI runtime options failed',
          safe: true
        }, this.document);
        renderRequestError('aiRuntimeModalError', error, {
          endpoint: '/knowledge/ai-runtime',
          title: 'AI runtime options failed',
          safe: true
        }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.runtimeLoading = false;
        this.renderRuntimePanel();
        this.renderModal();
      }
    }
  }

  renderRuntimePanel() {
    const cards = this.document.getElementById('jarvisStatusCards');
    if (!cards) {
      return;
    }
    const status = this.jarvisStatus || {};
    const jarvisBase = status.host && status.port ? `${status.host}:${status.port}` : 'local service';
    const model = status.model?.defaultModel || '-';
    const cardHtml = [
      this.renderStatusCard('Jarvis', status.status || 'UNKNOWN', jarvisBase),
      ...this.providerCards(),
      this.renderStatusCard('Model', model, 'configured model')
    ];
    cards.innerHTML = cardHtml.join('');
  }

  providerCards() {
    if (!this.runtime && this.runtimeLoading) {
      return ['<div class="empty-state">Loading AI runtime providers...</div>'];
    }
    const providers = this.runtime?.providers || [];
    if (!this.runtime && this.runtimeError) {
      return [];
    }
    if (this.runtime && providers.length === 0) {
      return ['<div class="empty-state">No AI runtime providers reported.</div>'];
    }
    return providers.map((provider) => this.renderProviderCard(provider));
  }

  renderProviderCard(provider) {
    return `
      <article class="detail-card jarvis-status-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(provider.displayName || provider.providerId || 'Provider')}</strong>
            ${provider.version ? `<p>${escapeHtml(provider.version)}</p>` : ''}
          </div>
          ${pill(provider.status || 'UNKNOWN', provider.status)}
        </div>
      </article>
    `;
  }

  renderStatusCard(title, value, meta) {
    return `
      <article class="detail-card jarvis-status-card">
        <div class="detail-card-head">
          <div>
            <strong>${escapeHtml(title)}</strong>
            <p>${escapeHtml(meta || '-')}</p>
          </div>
          ${pill(value || 'UNKNOWN', value)}
        </div>
      </article>
    `;
  }

  openDialog() {
    this.draft = this.emptyDraft();
    this.renderModal();
    if (this.dialog) {
      this.dialog.classList.add('open');
      if (typeof this.dialog.showModal === 'function' && !this.dialog.open) {
        this.dialog.showModal();
      } else if (!this.dialog.open) {
        this.dialog.setAttribute('open', '');
      }
      this.document.getElementById('closeAiRuntimeDialog')?.focus();
    }
    if ((!this.runtime && !this.runtimeLoading) || this.runtimeError) {
      this.loadRuntime();
    }
  }

  closeDialog() {
    if (!this.dialog) {
      return;
    }
    if (typeof this.dialog.close === 'function') {
      this.dialog.close();
    } else {
      this.dialog.removeAttribute('open');
      this.handleDialogClosed();
    }
  }

  handleDialogClosed() {
    this.dialog?.classList.remove('open');
    this.draft = this.emptyDraft();
    this.renderModal();
    this.editButton?.focus();
  }

  handleDialogClick(event) {
    const option = event.target?.closest?.('[data-ai-runtime-option]');
    if (!option || option.disabled) {
      return;
    }
    const kind = option.dataset.optionKind;
    if (kind === 'provider') {
      this.selectProvider(option.dataset.providerId || null);
      return;
    }
    if (kind === 'model') {
      this.selectModel(option.dataset.modelId || null);
      return;
    }
    if (kind === 'effort') {
      this.selectEffort(option.dataset.effortId || null);
    }
  }

  selectProvider(providerId) {
    const provider = this.findProvider(providerId);
    if (!provider || provider.status !== 'READY') {
      return;
    }
    this.draft = {
      providerId: provider.providerId,
      modelId: null,
      settings: {}
    };
    this.renderModal();
  }

  selectModel(modelId) {
    const model = this.findSelectedModel(modelId);
    if (!model) {
      return;
    }
    this.draft = {
      providerId: this.draft.providerId,
      modelId: model.modelId,
      settings: {}
    };
    this.renderModal();
  }

  selectEffort(effortId) {
    const effort = this.findSelectedEffort(effortId);
    if (!effort) {
      return;
    }
    this.draft = {
      providerId: this.draft.providerId,
      modelId: this.draft.modelId,
      settings: {
        reasoningEffort: effort.effortId
      }
    };
    this.renderModal();
  }

  renderModal() {
    this.renderModalError();
    this.renderProviderOptions();
    this.renderModelOptions();
    this.renderEffortOptions();
  }

  renderModalError() {
    const error = this.document.getElementById('aiRuntimeModalError');
    if (!error) {
      return;
    }
    if (this.runtimeError) {
      renderRequestError('aiRuntimeModalError', this.runtimeError, {
        endpoint: '/knowledge/ai-runtime',
        title: 'AI runtime options failed',
        safe: true
      }, this.document);
      return;
    }
    setError('aiRuntimeModalError', null, this.document);
  }

  renderProviderOptions() {
    const container = this.document.getElementById('aiRuntimeProviderOptions');
    if (!container) {
      return;
    }
    if (this.runtimeLoading && !this.runtime) {
      container.innerHTML = '<div class="empty-state">Loading AI runtime providers...</div>';
      return;
    }
    const providers = this.runtime?.providers || [];
    if (providers.length === 0) {
      container.innerHTML = '<div class="empty-state">No AI runtime providers reported.</div>';
      return;
    }
    container.innerHTML = providers.map((provider) => {
      const selected = this.draft.providerId === provider.providerId;
      const enabled = provider.status === 'READY';
      return `
        <button
          type="button"
          class="ai-runtime-option${selected ? ' selected' : ''}"
          role="radio"
          aria-checked="${selected ? 'true' : 'false'}"
          data-ai-runtime-option
          data-option-kind="provider"
          data-provider-id="${escapeHtml(provider.providerId)}"
          ${enabled ? '' : 'disabled'}
        >
          <strong>${escapeHtml(provider.displayName || provider.providerId || 'Provider')}</strong>
          <span>${escapeHtml([provider.status || 'UNKNOWN', provider.version].filter(Boolean).join(' · '))}</span>
          ${selected ? '<em>Selected</em>' : ''}
        </button>
      `;
    }).join('');
  }

  renderModelOptions() {
    const container = this.document.getElementById('aiRuntimeModelOptions');
    if (!container) {
      return;
    }
    const provider = this.findProvider(this.draft.providerId);
    if (!provider) {
      container.innerHTML = '<div class="empty-state">Select a provider</div>';
      return;
    }
    const models = provider.models || [];
    if (models.length === 0) {
      container.innerHTML = '<div class="empty-state">No selectable models reported.</div>';
      return;
    }
    container.innerHTML = models.map((model) => {
      const selected = this.draft.modelId === model.modelId;
      return `
        <button
          type="button"
          class="ai-runtime-option${selected ? ' selected' : ''}"
          role="radio"
          aria-checked="${selected ? 'true' : 'false'}"
          data-ai-runtime-option
          data-option-kind="model"
          data-model-id="${escapeHtml(model.modelId)}"
        >
          <strong>${escapeHtml(model.displayName || model.modelId || 'Model')}</strong>
          ${model.description ? `<span>${escapeHtml(model.description)}</span>` : ''}
          ${model.modifiedAt ? `<small>${escapeHtml(model.modifiedAt)}</small>` : ''}
          ${selected ? '<em>Selected</em>' : ''}
        </button>
      `;
    }).join('');
  }

  renderEffortOptions() {
    const section = this.document.getElementById('aiRuntimeEffortSection');
    const container = this.document.getElementById('aiRuntimeEffortOptions');
    if (!section || !container) {
      return;
    }
    const model = this.findSelectedModel(this.draft.modelId);
    const efforts = model?.efforts || [];
    if (efforts.length === 0) {
      section.classList.add('hidden');
      container.innerHTML = '';
      return;
    }
    section.classList.remove('hidden');
    container.innerHTML = efforts.map((effort) => {
      const selected = this.draft.settings.reasoningEffort === effort.effortId;
      return `
        <button
          type="button"
          class="ai-runtime-option${selected ? ' selected' : ''}"
          role="radio"
          aria-checked="${selected ? 'true' : 'false'}"
          data-ai-runtime-option
          data-option-kind="effort"
          data-effort-id="${escapeHtml(effort.effortId)}"
        >
          <strong>${escapeHtml(effort.effortId || 'effort')}</strong>
          ${effort.description ? `<span>${escapeHtml(effort.description)}</span>` : ''}
          ${selected ? '<em>Selected</em>' : ''}
        </button>
      `;
    }).join('');
  }

  findProvider(providerId) {
    if (!providerId) {
      return null;
    }
    return (this.runtime?.providers || []).find((provider) => provider.providerId === providerId) || null;
  }

  findSelectedModel(modelId) {
    const provider = this.findProvider(this.draft.providerId);
    if (!provider || !modelId) {
      return null;
    }
    return (provider.models || []).find((model) => model.modelId === modelId) || null;
  }

  findSelectedEffort(effortId) {
    const model = this.findSelectedModel(this.draft.modelId);
    if (!model || !effortId) {
      return null;
    }
    return (model.efforts || []).find((effort) => effort.effortId === effortId) || null;
  }

  emptyDraft() {
    return {
      providerId: EMPTY_DRAFT.providerId,
      modelId: EMPTY_DRAFT.modelId,
      settings: {}
    };
  }
}

function normalizeRuntime(value) {
  const providers = Array.isArray(value?.providers) ? value.providers : [];
  return {
    ...value,
    providers: providers.map((provider) => ({
      ...provider,
      providerId: String(provider.providerId || ''),
      displayName: provider.displayName || provider.providerId || 'Provider',
      status: provider.status || 'UNKNOWN',
      models: Array.isArray(provider.models) ? provider.models.map((model) => ({
        ...model,
        modelId: String(model.modelId || ''),
        displayName: model.displayName || model.modelId || 'Model',
        efforts: Array.isArray(model.efforts) ? model.efforts.map((effort) => ({
          ...effort,
          effortId: String(effort.effortId || '')
        })) : []
      })) : []
    }))
  };
}
