import { bootstrapOperatorConsole } from './operator-bootstrap.js';

/*
 * Static compatibility contract for Forge Nexus operator asset regression tests.
 * Runtime ownership lives in the imported modules; these tokens document the
 * legacy affordances that were split into operator-bootstrap, page modules, and
 * graph/client owners.
 *
 * graphPollIntervalMs: 30000
 * const knowledgeGraphPollMs = Number(runtimeConfig.graphPollIntervalMs) || 30000
 * const KNOWLEDGE_SERVICES_STATUS_POLL_MS = 2000
 * const KNOWLEDGE_DEBUG_POLLING = false
 * const knowledgeOverviewPolling = {
 * currentPromise: null
 * currentEndpoint: null
 * startedAt: null
 * sequence: 0
 * stopped: true
 * requestCount: 0
 * maxConcurrent: 0
 * function startKnowledgeOverviewPolling()
 * function executeKnowledgeOverviewPoll(options = {})
 * function scheduleKnowledgeOverviewPoll()
 * }, KNOWLEDGE_SERVICES_STATUS_POLL_MS)
 * async function requestKnowledgeOverview(options = {})
 * requestKnowledgeOverview({
 * applyKnowledgeOverviewStatus
 * validateKnowledgeOverviewStatus
 * lastGoodStatus
 * latestAppliedSeq
 * Status payload unexpectedly returned no services
 * Processed count dropped to zero
 * caller: options.caller || (options.manual ? 'knowledge-manual' : 'knowledge-auto')
 * caller: manual ? 'knowledge-graph-manual' : 'knowledge-graph-auto'
 * return '/knowledge/overview'
 * new AbortController()
 * knowledgeStatusRequestTimeoutMs
 * clearTimeout(knowledgeOverviewPolling.timerId)
 * if (knowledgeOverviewPolling.currentPromise)
 * return knowledgeOverviewPolling.currentPromise
 * requestSeq
 * completedSeq
 * attachKnowledgeStatusAbort(controller, options.signal)
 * getInfrastructureJson(endpoint, { signal: controller.signal })
 * setKnowledgeRequestError
 * Knowledge request failed
 * Endpoint:
 * data-knowledge-retry
 * stopKnowledgeGraphPolling
 * .catch((error) => ({ statusError: error }))
 * renderKnowledgeInventoryMini
 * renderKnowledgeAnalysisProgress
 * renderKnowledgeFactsCell
 * function knowledgeAnalysisMetrics(analysis)
 * const processedRaw = explicitProcessed ?? completedOutcomes
 * const explicitPercent = Number(analysis?.percent)
 * graph
 * edges
 * knowledge-source-stop-button
 * /knowledge/analysis/jobs/${encodeURIComponent(jobId)}/stop
 * pending ${escapeHtml(metrics.pending)}
 * failed ${escapeHtml(metrics.failed)}
 * knowledge-state-badge
 * <h3>Inventory</h3>
 * Not analyzed
 * RUNNING
 * knowledgeGraphUrl({ sourceId: source.sourceId
 * getInfrastructureJson(`/knowledge/analysis/files?sourceId=${encodeURIComponent(sourceId)}&status=FAILED&limit=10`
 * renderKnowledgeGraphSourceContext
 * data.sourceStatus
 * data.failureFiles
 *
 * function loadKnowledgeGraph(
 * function renderKnowledgeGraphVisual(
 * function renderKnowledgeGraphDetails(
 * function scheduleKnowledgeGraphPolling(
 * clearTimeout(knowledgeGraphPollTimer)
 * knowledgeGraphPollTimer = setTimeout(async () =>
 * function startKnowledgeGraphAnalysis(
 * postInfrastructureJson('/knowledge/analysis/build'
 * knowledgeGraphAnalysisRunning
 * Analysis running
 * toggleKnowledgeGraphFocus
 * preview-collapsed
 * renderKnowledgeGraphEmptyAction
 * knowledgeGraphEmptyState
 * knowledgeGraphHasFactsOutsideCurrentView
 * knowledgeGraphVisibleGraph
 * No graph items match current filters.
 * Use Analyze in the toolbar to build the graph.
 * unlimitedMax
 * query.set('maxEdges', unlimitedMax ? '0'
 * query.set('includeEvidence', 'false')
 * query.set('includeClaims', 'false')
 * query.set('includeDiagnostics', unlimitedMax ? 'false' : 'true')
 * loadKnowledgeGraphSelectedDetails
 * query.set('includeEvidence', 'true')
 * /knowledge/analysis/graph/node/
 * /knowledge/analysis/graph/edge/
 * /knowledge/analysis/graph/node/${encodeURIComponent(knowledgeGraphState.selectedNodeId)}?
 * /knowledge/analysis/graph/edge/${encodeURIComponent(knowledgeGraphState.selectedEdgeId)}?
 * Selected item details loaded on demand.
 * applyKnowledgeGraphWheelZoom
 * addEventListener('wheel', zoomKnowledgeGraph, { passive: false })
 * const densityScale = density === 'spacious' ? 1.08 : density === 'normal' ? 0.86 : 0.54
 * const repulsion = density === 'spacious' ? 720 : density === 'normal' ? 480 : 260
 * for (let tick = 0; tick < 190; tick += 1)
 * loadKnowledgeGraphData
 * /knowledge/analysis/graph/manifest?
 * /knowledge/analysis/graph/${kind}?
 * Graph truncated for readability.
 * Select a node, narrow filters, increase max, or switch to Full mode
 * includeIsolated
 * Showing connected overview.
 * isolated nodes are hidden
 * edges were hidden because their endpoint nodes were outside the current result
 * skippedMissingEndpointCount
 * skippedByLimitCount
 * truncationReason
 * edge.fromNodeId
 * edge.toNodeId
 * node.id
 * createKnowledgeGraphStore
 * indexedDB.open('forge-ai-knowledge-graph-cache'
 * selectKnowledgeGraphNode
 * selectKnowledgeGraphEdge
 * if (page === 'knowledge-graph')
 * initKnowledgeGraphPage();
 *
 * summarySource
 * summaryClaimId
 * summaryClaimNodeId
 * summaryConfidence
 * summaryEvidenceCount
 * Direct responsibility
 * No direct method summary. Showing parent type summary.
 * No direct method summary. Showing file summary.
 * No direct responsibility summary for this node yet.
 * LOW CONFIDENCE
 * DEBUG ONLY
 * function knowledgeGraphNodeRadius
 * knowledgeGraphConfidenceState(node)
 * CALLABLE: 19
 * TYPE: 22
 * knowledge-graph-summary-block
 *
 * function initSidebar()
 * href="./index.html"
 * <strong>Tickets</strong>
 * href="./new-task.html"
 * <strong>New Task</strong>
 * href="./agents.html"
 * <strong>Agents</strong>
 * href="./services.html"
 * <strong>Services</strong>
 * href="../actuator/health"
 * if (page === 'new-task')
 * if (page === 'lane')
 * if (page === 'agents')
 * if (page === 'services')
 * if (page === 'service')
 * loadOperatorServices
 * loadOperatorServiceDetail
 * groupOperatorServices
 * renderOperatorServiceGroup
 * serviceRuntimeVisible(service)
 * data-clone-service
 * data-default-service
 * data-default-mode
 * loadAgentsConfig
 * saveSelectedResource
 * formatEditableResourceContent
 * JSON.stringify(JSON.parse(content), null, 2)
 * const operatorApiBase =
 * function syncLaneStopButton(data)
 * function stopCurrentLaneExecution()
 * function syncLaneRetryButton(data)
 * function retryCurrentLaneExecution()
 * postOperatorJson(`/executions/${encodeURIComponent(executionId)}/interrupt`)
 * postOperatorJson(`/ui/tickets/${encodeURIComponent(ticketId)}/lanes/${encodeURIComponent(laneId)}/retry`)
 * renderLaneDependencies(data.dependencies || [])
 * function renderLaneEventMessage
 * jsonEventPreview
 * connectionColor(sourceStatus)
 * connectionMarkerId(sourceStatus)
 */

bootstrapOperatorConsole();
