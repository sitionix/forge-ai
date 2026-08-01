import { escapeHtml, pill, renderRequestError, setError } from './dom-render-helpers.js';
import { RequestCoordinator } from './request-coordinator.js';

const EMPTY_DRAFT = Object.freeze({
  providerId: null,
  modelId: null,
  effortId: null
});
const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 1440;
const MINUTES_PER_WEEK = 10080;

export class AiRuntimeView {
  constructor(options) {
    this.document = options.document || document;
    this.http = options.http;
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.disposed = false;
    this.jarvisStatus = null;
    this.runtime = null;
    this.activeProfile = null;
    this.runtimeError = null;
    this.applyError = null;
    this.runtimeLoading = false;
    this.activeProfileLoading = false;
    this.runtimeCatalogLoadId = 0;
    this.activeProfileLoadId = 0;
    this.applyInProgress = false;
    this.draft = this.emptyDraft();
    this.editButton = null;
    this.dialog = null;
    this.openListener = () => this.openDialog();
    this.closeListener = () => this.closeDialog();
    this.applyListener = () => this.applyDraft();
    this.dialogCloseListener = () => this.handleDialogClosed();
    this.dialogClickListener = (event) => this.handleDialogClick(event);
    this.dialogKeydownListener = (event) => {
      if (event.key === 'Escape' && !this.applyInProgress) {
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
    this.document.getElementById('applyAiRuntimeDialog')?.addEventListener('click', this.applyListener);
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
    this.document.getElementById('applyAiRuntimeDialog')?.removeEventListener('click', this.applyListener);
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

  async loadActiveProfile() {
    const loadId = this.activeProfileLoadId + 1;
    this.activeProfileLoadId = loadId;
    this.activeProfileLoading = true;
    this.renderRuntimePanel();
    this.renderModal();
    try {
      const result = await this.requestCoordinator.run('active-profile', ({ signal }) => this.http.get('/knowledge/active-profile', { signal }));
      if (!result.applied || this.disposed || loadId !== this.activeProfileLoadId) {
        return null;
      }
      this.activeProfile = normalizeActiveProfile(result.value);
      this.applyError = null;
      this.resetDraftToActive();
      setError('aiRuntimeError', null, this.document);
      return this.activeProfile;
    } catch (error) {
      if (!this.disposed && loadId === this.activeProfileLoadId) {
        this.activeProfile = null;
        this.resetDraftToActive();
        renderRequestError('aiRuntimeError', error, {
          endpoint: '/knowledge/active-profile',
          title: 'Active profile failed',
          safe: true
        }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed && loadId === this.activeProfileLoadId) {
        this.activeProfileLoading = false;
        this.renderRuntimePanel();
        this.renderModal();
      }
    }
  }

  async loadRuntimeCatalog() {
    const loadId = this.runtimeCatalogLoadId + 1;
    this.runtimeCatalogLoadId = loadId;
    this.runtimeLoading = true;
    this.runtimeError = null;
    this.runtime = null;
    this.applyError = null;
    this.resetDraftToActive();
    this.renderModal();
    try {
      const result = await this.requestCoordinator.run(
        'ai-runtime-catalog',
        ({ signal }) => this.http.get('/knowledge/ai-runtime', { signal })
      );
      if (!result.applied || this.disposed || loadId !== this.runtimeCatalogLoadId) {
        return null;
      }
      this.runtime = normalizeRuntime(result.value);
      this.runtimeError = null;
      this.resetDraftToActive();
      setError('aiRuntimeModalError', null, this.document);
      return this.runtime;
    } catch (error) {
      if (!this.disposed && loadId === this.runtimeCatalogLoadId) {
        this.runtime = null;
        this.runtimeError = error;
        renderRequestError('aiRuntimeModalError', error, {
          endpoint: '/knowledge/ai-runtime',
          title: 'AI runtime catalog failed',
          safe: true
        }, this.document);
      }
      return null;
    } finally {
      if (!this.disposed && loadId === this.runtimeCatalogLoadId) {
        this.runtimeLoading = false;
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
    const cardHtml = [
      this.renderStatusCard('Jarvis', status.status || 'UNKNOWN', jarvisBase),
      this.renderActiveLlmCard(),
      this.renderUsageSection()
    ].filter(Boolean);
    cards.innerHTML = cardHtml.join('');
  }

  renderActiveLlmCard() {
    const profile = this.activeProfile;
    if (!profile?.llmProfile) {
      return this.activeProfileLoading
        ? this.renderStatusCard('Active LLM', 'Loading', 'active profile')
        : this.renderStatusCard('Active LLM', '-', 'active profile');
    }
    const effort = profile.llmProfile.effort?.effortId;
    const providerName = profile.llmProfile.providerDisplayName || profile.llmProfile.providerId;
    const modelName = profile.llmProfile.modelDisplayName || profile.llmProfile.modelId;
    const meta = [
      providerName,
      profile.llmProfile.modelId,
      effort ? `effort ${effort}` : null
    ].filter(Boolean).join(' · ');
    return this.renderStatusCard('Active LLM', modelName, meta);
  }

  renderUsageSection() {
    const windows = this.activeProfile?.usage?.windows;
    if (!Array.isArray(windows) || windows.length === 0) {
      return '';
    }
    return `
      <section class="detail-card ai-runtime-usage" aria-label="LLM usage">
        <h3>Usage</h3>
        <div class="ai-runtime-usage-windows">
          ${windows.map((window) => this.renderUsageWindow(window)).join('')}
        </div>
      </section>
    `;
  }

  renderUsageWindow(window) {
    const used = clampPercent(window.usedPercent);
    const remaining = Math.max(0, 100 - used);
    return `
      <article class="ai-runtime-usage-window">
        <div class="ai-runtime-usage-row">
          <strong>${escapeHtml(durationLabel(window.windowDurationMinutes))}</strong>
          <span>${escapeHtml(`${used}% used · ${remaining}% remaining`)}</span>
        </div>
        <div class="ai-runtime-progress" aria-hidden="true">
          <span style="width: ${used}%"></span>
        </div>
        <p>Resets ${escapeHtml(formatResetAt(window.resetAt))}</p>
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
    this.applyError = null;
    this.runtimeError = null;
    this.runtime = null;
    this.runtimeLoading = true;
    this.resetDraftToActive();
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
    this.loadRuntimeCatalog();
  }

  closeDialog() {
    if (!this.dialog || this.applyInProgress) {
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
    this.applyError = null;
    this.renderModal();
    this.editButton?.focus();
  }

  handleDialogClick(event) {
    const option = event.target?.closest?.('[data-ai-runtime-option]');
    if (!option || option.disabled || this.applyInProgress || this.runtimeLoading || this.runtimeError) {
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
      effortId: null
    };
    this.applyError = null;
    this.renderModal();
  }

  selectModel(modelId) {
    const model = this.findSelectedModel(modelId);
    if (!model) {
      return;
    }
    const efforts = model.efforts || [];
    this.draft = {
      providerId: this.draft.providerId,
      modelId: model.modelId,
      effortId: efforts.length === 1 ? efforts[0].effortId : null
    };
    this.applyError = null;
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
      effortId: effort.effortId
    };
    this.applyError = null;
    this.renderModal();
  }

  async applyDraft() {
    if (!this.canApply()) {
      return null;
    }
    this.applyInProgress = true;
    this.applyError = null;
    this.renderModal();
    const body = {
      expectedRevision: this.activeProfile.revision,
      providerId: this.draft.providerId,
      modelId: this.draft.modelId,
      effort: this.draft.effortId ? { effortId: this.draft.effortId } : null
    };
    try {
      const result = await this.http.put('/knowledge/active-profile/llm-profile', body);
      if (this.disposed) {
        return null;
      }
      this.activeProfile = {
        revision: Number(result?.revision || this.activeProfile.revision + 1),
        llmProfile: normalizeLlmProfile(result?.llmProfile),
        usage: null
      };
      this.resetDraftToActive();
      await this.loadActiveProfile();
      if (this.disposed) {
        return null;
      }
      this.applyInProgress = false;
      this.closeDialog();
      this.editButton?.focus();
      return result;
    } catch (error) {
      if (!this.disposed) {
        if (Number(error?.status) === 409 || error?.code === 'ACTIVE_PROFILE_REVISION_CONFLICT') {
          await this.handleRevisionConflict(error);
        } else {
          this.applyError = error;
          renderRequestError('aiRuntimeModalError', error, {
            endpoint: '/knowledge/active-profile/llm-profile',
            title: 'Active profile update failed',
            safe: true
          }, this.document);
        }
      }
      return null;
    } finally {
      if (!this.disposed) {
        this.applyInProgress = false;
        this.renderRuntimePanel();
        this.renderModal();
      }
    }
  }

  async handleRevisionConflict(error) {
    this.applyError = Object.assign(new Error('The active profile changed. Review the latest selection.'), {
      code: error?.code || 'ACTIVE_PROFILE_REVISION_CONFLICT',
      status: 409
    });
    try {
      const activeProfile = await this.http.get('/knowledge/active-profile');
      if (!this.disposed) {
        this.activeProfile = normalizeActiveProfile(activeProfile);
        this.resetDraftToActive();
      }
    } catch (_) {
      this.applyError = error;
    }
  }

  renderModal() {
    this.renderModalError();
    this.renderProviderOptions();
    this.renderModelOptions();
    this.renderEffortOptions();
    this.renderApplyState();
  }

  renderModalError() {
    const error = this.document.getElementById('aiRuntimeModalError');
    if (!error) {
      return;
    }
    if (this.applyError) {
      renderRequestError('aiRuntimeModalError', this.applyError, {
        endpoint: '/knowledge/active-profile/llm-profile',
        title: this.applyError.status === 409 ? 'Active profile changed' : 'Active profile update failed',
        safe: true
      }, this.document);
      return;
    }
    if (this.runtimeError) {
      renderRequestError('aiRuntimeModalError', this.runtimeError, {
        endpoint: '/knowledge/ai-runtime',
        title: 'AI runtime catalog failed',
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
    if (this.runtimeLoading) {
      container.innerHTML = '<div class="empty-state">Loading AI runtime providers...</div>';
      return;
    }
    if (this.runtimeError) {
      container.innerHTML = '<div class="empty-state">Runtime catalog unavailable.</div>';
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
    if (this.runtimeLoading) {
      container.innerHTML = '<div class="empty-state">Loading AI runtime models...</div>';
      return;
    }
    if (this.runtimeError) {
      container.innerHTML = '<div class="empty-state">Runtime catalog unavailable.</div>';
      return;
    }
    const provider = this.findProvider(this.draft.providerId);
    if (!provider) {
      container.innerHTML = '<div class="empty-state">Select a provider</div>';
      return;
    }
    if (provider.status !== 'READY') {
      container.innerHTML = '<div class="empty-state">Provider is not selectable.</div>';
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
      const selected = this.draft.effortId === effort.effortId;
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

  renderApplyState() {
    const button = this.document.getElementById('applyAiRuntimeDialog');
    const note = this.document.querySelector('.ai-runtime-note');
    if (!button) {
      return;
    }
    button.disabled = !this.canApply();
    button.textContent = this.applyInProgress ? 'Applying...' : 'Apply';
    if (note) {
      note.textContent = this.applyNote();
    }
  }

  applyNote() {
    if (this.applyInProgress) {
      return 'Applying active profile...';
    }
    if (this.runtimeLoading) {
      return 'Loading runtime catalog...';
    }
    if (this.runtimeError) {
      return 'Runtime catalog unavailable';
    }
    if (!this.draft.providerId || !this.draft.modelId) {
      return 'Select a provider and model';
    }
    const model = this.findSelectedModel(this.draft.modelId);
    const efforts = model?.efforts || [];
    if (efforts.length > 0 && !this.draft.effortId) {
      return 'Select a reasoning effort';
    }
    if (!this.draftDiffersFromActive()) {
      return 'No changes to apply';
    }
    return 'Ready to apply';
  }

  canApply() {
    if (this.applyInProgress || this.runtimeLoading || this.runtimeError || !this.runtime || !this.activeProfile?.llmProfile) {
      return false;
    }
    if (!this.draft.providerId || !this.draft.modelId) {
      return false;
    }
    const model = this.findSelectedModel(this.draft.modelId);
    const efforts = model?.efforts || [];
    if (efforts.length > 0 && !this.draft.effortId) {
      return false;
    }
    return this.draftDiffersFromActive();
  }

  draftDiffersFromActive() {
    const active = this.activeProfile?.llmProfile;
    if (!active) {
      return false;
    }
    return this.draft.providerId !== active.providerId
      || this.draft.modelId !== active.modelId
      || (this.draft.effortId || null) !== (active.effort?.effortId || null);
  }

  resetDraftToActive() {
    const profile = this.activeProfile?.llmProfile;
    this.draft = profile ? {
      providerId: profile.providerId,
      modelId: profile.modelId,
      effortId: profile.effort?.effortId || null
    } : this.emptyDraft();
  }

  findProvider(providerId) {
    if (!providerId) {
      return null;
    }
    return (this.runtime?.providers || []).find((provider) => provider.providerId === providerId) || null;
  }

  findModel(providerId, modelId) {
    const provider = this.findProvider(providerId);
    if (!provider || !modelId) {
      return null;
    }
    return (provider.models || []).find((model) => model.modelId === modelId) || null;
  }

  findSelectedModel(modelId) {
    return this.findModel(this.draft.providerId, modelId);
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
      effortId: EMPTY_DRAFT.effortId
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

function normalizeActiveProfile(value) {
  return {
    revision: Number(value?.revision || 0),
    llmProfile: normalizeLlmProfile(value?.llmProfile),
    usage: value?.usage === null ? null : normalizeUsage(value?.usage)
  };
}

function normalizeLlmProfile(value) {
  return {
    providerId: String(value?.providerId || ''),
    modelId: String(value?.modelId || ''),
    effort: value?.effort ? { effortId: String(value.effort.effortId || '') } : null,
    providerDisplayName: value?.providerDisplayName ? String(value.providerDisplayName) : null,
    modelDisplayName: value?.modelDisplayName ? String(value.modelDisplayName) : null
  };
}

function normalizeUsage(value) {
  const windows = Array.isArray(value?.windows) ? value.windows : [];
  return {
    windows: windows.map((window) => ({
      kind: String(window.kind || ''),
      usedPercent: clampPercent(window.usedPercent),
      windowDurationMinutes: Number(window.windowDurationMinutes || 0),
      resetAt: String(window.resetAt || '')
    }))
  };
}

function clampPercent(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round(number)));
}

function durationLabel(minutes) {
  const value = Number(minutes);
  if (value === MINUTES_PER_DAY) {
    return 'Daily usage';
  }
  if (value === MINUTES_PER_WEEK) {
    return 'Weekly usage';
  }
  if (Number.isFinite(value) && value > 0 && value % MINUTES_PER_HOUR === 0) {
    const hours = value / MINUTES_PER_HOUR;
    return `${hours}-hour usage`;
  }
  return `${Number.isFinite(value) && value > 0 ? value : 0}-minute usage`;
}

function formatResetAt(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}
