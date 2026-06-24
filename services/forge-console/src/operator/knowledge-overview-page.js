import {
  cssEscape,
  escapeHtml,
  nonNegativeNumber,
  optionalNonNegativeNumber,
  renderRequestError,
  statusClass,
  timeOnly
} from './dom-render-helpers.js';
import { PollingCoordinator } from './polling-coordinator.js';
import { RequestCoordinator } from './request-coordinator.js';

const OVERVIEW_ENDPOINT = '/knowledge/overview';
const ACTIVE_STATUSES = new Set(['QUEUED', 'RUNNING', 'STOP_REQUESTED']);

export class KnowledgeOverviewPage {
  constructor(options) {
    this.document = options.document || document;
    this.window = options.window || window;
    this.http = options.http;
    this.requestCoordinator = options.requestCoordinator || new RequestCoordinator();
    this.runtimeConfig = options.runtimeConfig || {};
    this.disposed = false;
    this.lastGoodStatus = null;
    this.lastSuccessAt = null;
    this.errorCount = 0;
    this.requestCount = 0;
    this.activeCount = 0;
    this.maxConcurrent = 0;
    this.currentPromise = null;
    this.latestAppliedSeq = 0;
    this.analysisStartsInFlight = new Set();
    this.refreshListener = () => this.load({ manual: true, caller: 'knowledge-manual' });
    this.retryListener = (event) => {
      if (event.target.closest('[data-knowledge-retry]')) {
        this.load({ manual: true, caller: 'knowledge-retry' });
      }
    };
    this.actionListener = (event) => this.handleSourceAction(event);
    this.beforeUnloadListener = () => this.dispose();
    this.polling = new PollingCoordinator({
      document: this.document,
      setTimeout: this.window.setTimeout.bind(this.window),
      clearTimeout: this.window.clearTimeout.bind(this.window),
      activeIntervalMs: Number(this.runtimeConfig.activeJobPollIntervalMs) || 2000,
      idleIntervalMs: Number(this.runtimeConfig.statusPollIntervalMs) || 15000,
      hiddenIntervalMs: null,
      isActive: (status) => this.hasActiveAnalysis(status),
      poll: () => this.load({ manual: false, caller: 'knowledge-auto' })
    });
  }

  mount() {
    this.disposed = false;
    this.renderLoadingState();
    this.document.getElementById('refreshKnowledge')?.addEventListener('click', this.refreshListener);
    this.document.getElementById('knowledgeError')?.addEventListener('click', this.retryListener);
    this.document.getElementById('knowledgeSourcesBody')?.addEventListener('click', this.actionListener);
    this.window.addEventListener('beforeunload', this.beforeUnloadListener);
    this.polling.start({ immediate: true });
  }

  dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.polling.dispose();
    this.requestCoordinator.dispose();
    this.document.getElementById('refreshKnowledge')?.removeEventListener('click', this.refreshListener);
    this.document.getElementById('knowledgeError')?.removeEventListener('click', this.retryListener);
    this.document.getElementById('knowledgeSourcesBody')?.removeEventListener('click', this.actionListener);
    this.window.removeEventListener('beforeunload', this.beforeUnloadListener);
  }

  load(options = {}) {
    if (this.disposed) {
      return Promise.resolve(null);
    }
    if (options.manual) {
      this.polling.clearTimer();
    }
    this.requestCount += 1;
    this.activeCount += 1;
    this.maxConcurrent = Math.max(this.maxConcurrent, this.activeCount);
    const promise = this.requestCoordinator.run('knowledge-overview', async ({ signal, sequence }) => {
      const payload = await this.http.get(OVERVIEW_ENDPOINT, { signal });
      return { payload: normalizeKnowledgeOverviewPayload(payload), sequence };
    }, { abortPrevious: Boolean(options.manual) });
    this.currentPromise = promise;
    return promise
      .then((result) => {
        if (!result.applied || this.disposed) {
          return null;
        }
        const status = this.applySnapshot(result.value.payload, result.value.sequence);
        this.render(status);
        renderRequestError('knowledgeError', null, {}, this.document);
        this.errorCount = 0;
        this.lastSuccessAt = Date.now();
        return status;
      })
      .catch((error) => {
        if (this.disposed || error?.name === 'AbortError') {
          return null;
        }
        this.errorCount += 1;
        renderRequestError('knowledgeError', error, {
          endpoint: OVERVIEW_ENDPOINT,
          retry: true,
          transient: this.errorCount === 1,
          title: 'Knowledge request failed'
        }, this.document);
        if (!this.lastGoodStatus) {
          const body = this.document.getElementById('knowledgeSourcesBody');
          if (body) {
            body.innerHTML = '<tr><td colspan="5">Unable to load services.</td></tr>';
          }
        }
        const updated = this.document.getElementById('knowledgeUpdated');
        if (updated) {
          updated.textContent = 'failed';
        }
        return null;
      })
      .finally(() => {
        this.activeCount = Math.max(0, this.activeCount - 1);
        if (this.currentPromise === promise) {
          this.currentPromise = null;
        }
      });
  }

  applySnapshot(status, sequence) {
    const validation = validateKnowledgeOverviewSnapshot(status, this.lastGoodStatus);
    if (!validation.valid) {
      const error = new Error(validation.reason || 'Knowledge status snapshot was invalid');
      error.code = 'KNOWLEDGE_STATUS_SNAPSHOT_REJECTED';
      error.endpoint = OVERVIEW_ENDPOINT;
      throw error;
    }
    if (sequence < this.latestAppliedSeq) {
      return this.lastGoodStatus;
    }
    this.latestAppliedSeq = sequence;
    this.lastGoodStatus = status;
    return status;
  }

  render(status) {
    if (this.disposed || !status) {
      return;
    }
    renderKnowledgeSources(status, this.document);
    const updated = this.document.getElementById('knowledgeUpdated');
    if (updated) {
      updated.textContent = `updated ${timeOnly()}`;
    }
  }

  renderLoadingState() {
    const body = this.document.getElementById('knowledgeSourcesBody');
    if (body && !this.lastGoodStatus) {
      body.innerHTML = '<tr><td colspan="5">Loading services...</td></tr>';
    }
  }

  async handleSourceAction(event) {
    const stopButton = event.target.closest('.knowledge-source-stop-button');
    if (stopButton) {
      await this.stopAnalysis(stopButton.dataset.sourceId || '', stopButton.dataset.jobId || '', stopButton);
      return;
    }
    const analyzeButton = event.target.closest('.knowledge-source-analysis-button');
    if (analyzeButton?.dataset.sourceId) {
      await this.startAnalysis(analyzeButton.dataset.sourceId, analyzeButton);
    }
  }

  async startAnalysis(sourceId, button) {
    if (this.analysisStartsInFlight.has(sourceId)) {
      return;
    }
    this.analysisStartsInFlight.add(sourceId);
    if (button) {
      button.disabled = true;
      button.textContent = 'Starting...';
    }
    try {
      await this.http.post('/knowledge/analysis/build', {
        sourceIds: sourceId ? [sourceId] : [],
        groups: [],
        force: false,
        maxFiles: null,
        concurrency: 1,
        selection: 'DEFAULT'
      });
      renderRequestError('knowledgeAnalysisError', null, {}, this.document);
      const status = await this.load({ manual: true, caller: 'knowledge-analysis-start' });
      if (status) {
        this.polling.lastResult = status;
      }
      if (!this.disposed) {
        this.polling.schedule();
      }
    } catch (error) {
      renderRequestError('knowledgeAnalysisError', error, {
        endpoint: '/knowledge/analysis/build',
        title: 'Knowledge action failed'
      }, this.document);
    } finally {
      this.analysisStartsInFlight.delete(sourceId);
      if (button) {
        button.disabled = false;
        button.textContent = 'Analyze';
      }
    }
  }

  async stopAnalysis(sourceId, jobId, button) {
    if (!jobId) {
      return;
    }
    if (button) {
      button.disabled = true;
      button.textContent = 'Stopping...';
    }
    try {
      await this.http.post(`/knowledge/analysis/jobs/${encodeURIComponent(jobId)}/stop`, {});
      renderRequestError('knowledgeAnalysisError', null, {}, this.document);
      await this.load({ manual: true, caller: 'knowledge-analysis-stop' });
    } catch (error) {
      renderRequestError('knowledgeAnalysisError', error, {
        endpoint: `/knowledge/analysis/jobs/${encodeURIComponent(jobId)}/stop`,
        title: 'Knowledge action failed'
      }, this.document);
      if (button) {
        button.disabled = false;
        button.textContent = 'Stop';
      }
    }
  }

  hasActiveAnalysis(status = this.lastGoodStatus) {
    if (isActiveAnalysisJob(status?.activeJob)) {
      return true;
    }
    return (status?.services || []).some((source) => ACTIVE_STATUSES.has(String(source.analysis?.status || '').toUpperCase()));
  }

  testApi() {
    return {
      state: this,
      polling: this.polling,
      requestKnowledgeOverview: (options = {}) => this.load({ manual: true, ...options }),
      stopKnowledgeStatusPolling: () => this.polling.stop(),
      dispose: () => this.dispose()
    };
  }
}

export function normalizeKnowledgeOverviewPayload(payload) {
  if (Array.isArray(payload?.services)) {
    return payload;
  }
  const sources = Array.isArray(payload?.sources) ? payload.sources : [];
  const services = sources.map((source) => {
    const inventory = source.inventory || {};
    const analysis = source.analysis || {};
    return {
      sourceId: source.sourceId,
      label: source.displayName || source.label || source.sourceId,
      group: source.group || null,
      rootExists: source.rootExists,
      tags: source.tags || [],
      inventory: {
        status: inventory.status,
        eligibleFileCount: inventory.fileCount ?? inventory.eligibleFileCount ?? 0,
        skippedCount: inventory.skippedCount ?? 0
      },
      analysis: {
        status: analysis.status,
        inventoryFileCount: analysis.totalFiles ?? analysis.inventoryFileCount ?? 0,
        analyzedFileCount: analysis.succeededFiles ?? analysis.analyzedFileCount ?? 0,
        processedFileCount: analysis.processedFiles ?? analysis.processedFileCount ?? 0,
        failedFileCount: analysis.failedFiles ?? analysis.failedFileCount ?? 0,
        skippedTooLargeFileCount: analysis.skippedFiles ?? analysis.skippedTooLargeFileCount ?? 0,
        pendingFileCount: analysis.pendingFiles ?? analysis.pendingFileCount ?? 0,
        percent: analysis.percent ?? 0,
        activeJobId: analysis.activeJobId,
        activeJobMode: analysis.activeJobMode,
        activeJobSelectedFileCount: analysis.activeJobSelectedFileCount,
        activeJobProcessedFileCount: analysis.activeJobProcessedFileCount,
        activeJobFailedFileCount: analysis.activeJobFailedFileCount,
        activeJobCurrentRelativePath: analysis.activeJobCurrentRelativePath
      }
    };
  });
  return {
    version: payload?.version ?? 0,
    updatedAt: payload?.updatedAt ?? null,
    services,
    activeJob: payload?.activeJob || null
  };
}

export function validateKnowledgeOverviewSnapshot(serviceStatus, previous) {
  const supportedStatuses = new Set(['NOT_ANALYZED', 'RUNNING', 'STOP_REQUESTED', 'PARTIAL', 'COMPLETED', 'EMPTY']);
  if (!serviceStatus || typeof serviceStatus !== 'object') {
    return { valid: false, reason: 'Malformed status payload' };
  }
  if (!Array.isArray(serviceStatus.services)) {
    return { valid: false, reason: 'Status payload is missing services' };
  }
  if (serviceStatus.services.length === 0 && Array.isArray(previous?.services) && previous.services.length > 0) {
    return { valid: false, reason: 'Status payload unexpectedly returned no services' };
  }
  const previousBySource = new Map((previous?.services || []).map((service) => [service.sourceId, service]));
  const currentBySource = new Map();
  for (const service of serviceStatus.services) {
    if (!service?.sourceId) {
      return { valid: false, reason: 'Status payload contains a service without sourceId' };
    }
    currentBySource.set(service.sourceId, service);
    const analysis = service.analysis || {};
    const inventory = service.inventory || {};
    const inventoryEligible = optionalNonNegativeNumber(inventory.eligibleFileCount);
    const inventoryCount = optionalNonNegativeNumber(analysis.inventoryFileCount);
    const analyzed = optionalNonNegativeNumber(analysis.analyzedFileCount);
    const processed = optionalNonNegativeNumber(analysis.processedFileCount);
    const failed = optionalNonNegativeNumber(analysis.failedFileCount);
    const pending = optionalNonNegativeNumber(analysis.pendingFileCount);
    const status = String(analysis.status || '').toUpperCase();
    if ([inventoryEligible, inventoryCount, analyzed, processed, failed, pending].some((value) => value === null)) {
      return { valid: false, reason: `Missing KPI counters for ${service.sourceId}` };
    }
    if (!supportedStatuses.has(status)) {
      return { valid: false, reason: `Unsupported analysis status for ${service.sourceId}` };
    }
    if (analyzed > inventoryCount || processed > inventoryCount || failed > inventoryCount || analyzed > processed) {
      return { valid: false, reason: `Impossible analysis counters for ${service.sourceId}` };
    }
  }
  for (const sourceId of previousBySource.keys()) {
    if (!currentBySource.has(sourceId)) {
      return { valid: false, reason: `Source ${sourceId} disappeared from status payload` };
    }
  }
  if (serviceStatus.activeJob?.sourceId && !currentBySource.has(serviceStatus.activeJob.sourceId)) {
    return { valid: false, reason: `Active job source ${serviceStatus.activeJob.sourceId} is missing from status payload` };
  }
  return { valid: true, reason: null };
}

export function renderKnowledgeSources(data, documentRef = document) {
  const body = documentRef.getElementById('knowledgeSourcesBody');
  const diagnostics = documentRef.getElementById('knowledgeDiagnostics');
  if (!body) {
    return;
  }
  const services = data.services || [];
  documentRef.defaultView.__forgeKnowledgeSourceStatus = services;
  if (services.length === 0) {
    body.innerHTML = '<tr><td colspan="5">No services configured.</td></tr>';
  } else {
    const existingRows = services.map((service) => body.querySelector(`[data-source-row="${cssEscape(service.sourceId || '')}"]`));
    const canUpdateInPlace = existingRows.length === services.length && existingRows.every(Boolean);
    if (canUpdateInPlace) {
      services.forEach((service, index) => {
        existingRows[index].innerHTML = renderKnowledgeSourceCells(service);
      });
    } else {
      body.innerHTML = services.map((service) => renderKnowledgeSourceRow(service)).join('');
    }
  }
  if (diagnostics) {
    diagnostics.innerHTML = '';
  }
}

function renderKnowledgeSourceRow(source) {
  return `
    <tr data-source-row="${escapeHtml(source.sourceId || '')}">
      ${renderKnowledgeSourceCells(source)}
    </tr>
  `;
}

function renderKnowledgeSourceCells(source) {
  const analysis = source.analysis || {};
  const inventory = source.inventory || {};
  const tags = source.tags || [];
  const visibleTags = tags.slice(0, 3);
  const extraTags = Math.max(0, tags.length - visibleTags.length);
  const rootLabel = source.rootExists ? 'OK' : (source.rootExists === false ? 'missing' : 'false');
  const rootClass = source.rootExists ? 'knowledge-root-ok' : 'knowledge-root-missing';
  const tagHtml = visibleTags.length || extraTags
    ? `<div class="knowledge-chip-row">
        ${visibleTags.map((tag) => `<span class="knowledge-chip">${escapeHtml(tag)}</span>`).join('')}
        ${extraTags ? `<span class="knowledge-chip">+${escapeHtml(extraTags)}</span>` : ''}
      </div>`
    : '';
  const isRunning = ACTIVE_STATUSES.has(String(analysis.status || '').toUpperCase()) && analysis.activeJobId;
  const actionButton = isRunning
    ? `<button class="button knowledge-source-stop-button" data-source-id="${escapeHtml(source.sourceId || '')}" data-job-id="${escapeHtml(analysis.activeJobId || '')}">Stop</button>`
    : `<button class="button knowledge-source-analysis-button" data-source-id="${escapeHtml(source.sourceId || '')}">Analyze</button>`;
  return `
    <td>
      <div class="knowledge-source-label">
        <strong>${escapeHtml(source.sourceId || '-')}</strong>
        <span>${escapeHtml(source.label || '-')}</span>
        <small>${escapeHtml(source.group || '-')} · <span class="${rootClass}">${escapeHtml(rootLabel)}</span></small>
        ${tagHtml}
      </div>
    </td>
    <td>${renderKnowledgeInventoryMini(inventory)}</td>
    <td>${renderKnowledgeAnalysisProgress(analysis)}</td>
    <td>${renderKnowledgeFactsCell(source.facts || {})}</td>
    <td>
      <div class="knowledge-source-actions">
        ${actionButton}
        <a class="button ghost dark knowledge-source-graph-link" href="${escapeHtml(knowledgeGraphUrl({ sourceId: source.sourceId || '', flowDomain: 'CODE', depth: 2 }))}">Graph</a>
      </div>
    </td>
  `;
}

function renderKnowledgeInventoryMini(inventory) {
  const eligible = inventory?.eligibleFileCount ?? 0;
  const skipped = inventory?.skippedCount;
  return `
    <div class="knowledge-mini-status">
      <strong>${escapeHtml(eligible)} files</strong>
      ${skipped !== null && skipped !== undefined ? `<small>skipped ${escapeHtml(skipped)}</small>` : ''}
    </div>
  `;
}

function renderKnowledgeFactsCell(facts) {
  if (!facts || (facts.symbolCount === undefined && facts.relationCount === undefined)) {
    return '<div class="knowledge-facts-cell"><strong>Graph</strong><small>open details</small></div>';
  }
  return `
    <div class="knowledge-facts-cell">
      <strong>symbols ${escapeHtml(facts?.symbolCount ?? 0)}</strong>
      <small>relations ${escapeHtml(facts?.relationCount ?? 0)}</small>
    </div>
  `;
}

function renderKnowledgeAnalysisProgress(analysis) {
  if (!analysis || Object.keys(analysis).length === 0) {
    return `
      <div class="knowledge-progress">
        <div class="knowledge-service-state">
          <strong class="knowledge-state-badge ${escapeHtml(statusClass('NOT_ANALYZED'))}">Not analyzed</strong>
        </div>
        <small>0 / 0</small>
      </div>
    `;
  }
  const metrics = knowledgeAnalysisMetrics(analysis);
  const status = String(analysis.status || '').toUpperCase();
  return `
    <div class="knowledge-progress">
      <div class="knowledge-service-state">
        <strong class="knowledge-state-badge ${escapeHtml(statusClass(status))}">${escapeHtml(status || 'NOT_ANALYZED')}</strong>
      </div>
      <div class="knowledge-progress-meta">
        <strong>${escapeHtml(metrics.processed)} / ${escapeHtml(metrics.total)}</strong>
        <span>${escapeHtml(metrics.percent)}%</span>
      </div>
      <div class="knowledge-progress-track">
        <span style="width:${Math.max(0, Math.min(100, metrics.percent))}%"></span>
      </div>
      <small>
        pending ${escapeHtml(metrics.pending)}
        failed ${escapeHtml(metrics.failed)}
      </small>
    </div>
  `;
}

function knowledgeAnalysisMetrics(analysis) {
  const total = nonNegativeNumber(analysis?.inventoryFileCount ?? analysis?.totalFiles ?? analysis?.totalFileCount);
  const analyzed = nonNegativeNumber(analysis?.analyzedFileCount);
  const failed = nonNegativeNumber(analysis?.failedFileCount);
  const skipped = nonNegativeNumber(analysis?.skippedTooLargeFileCount);
  const explicitProcessed = optionalNonNegativeNumber(analysis?.processedFileCount);
  const explicitPending = optionalNonNegativeNumber(analysis?.pendingFileCount);
  const completedOutcomes = analyzed + failed + skipped;
  const processedRaw = explicitProcessed ?? completedOutcomes;
  const processed = total > 0 ? Math.min(processedRaw, total) : processedRaw;
  const derivedPending = Math.max(total - processed, 0);
  const pending = explicitPending !== null ? Math.min(explicitPending, derivedPending) : derivedPending;
  const explicitPercent = Number(analysis?.percent);
  const percent = Number.isFinite(explicitPercent) ? explicitPercent : (total > 0 ? Math.round((processed / total) * 1000) / 10 : 0);
  return { total, analyzed, failed, skipped, processed, pending, percent };
}

function knowledgeGraphUrl(params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value) !== '') {
      query.set(key, value);
    }
  });
  return `./knowledge-graph.html?${query.toString()}`;
}

function isActiveAnalysisJob(job) {
  return job && ACTIVE_STATUSES.has(String(job.status || '').toUpperCase());
}
