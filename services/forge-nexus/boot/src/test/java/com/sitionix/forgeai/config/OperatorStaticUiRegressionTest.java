package com.sitionix.forgeai.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorStaticUiRegressionTest {

    private static final Path OPERATOR_UI_DIR = Path.of("../../forge-console/src/operator");
    private static final Path STATIC_UI_DIR = Path.of("src/main/resources/static");

    @Test
    void givenRootStaticIndex_whenRendered_thenRedirectToOperatorUi() throws Exception {
        final String html = Files.readString(STATIC_UI_DIR.resolve("index.html"), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("url=./operator/index.html")
                .contains("href=\"./operator/index.html\"");
    }

    @Test
    void givenTicketsPage_whenRendered_thenKeepExistingPrimaryActions() throws Exception {
        final String html = this.read("index.html");

        assertThat(html)
                .contains("id=\"refreshTickets\"")
                .contains(">Refresh</button>")
                .doesNotContain("href=\"../actuator/health\">Health</a>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenTicketPage_whenRendered_thenKeepExistingTicketGraphActions() throws Exception {
        final String html = this.read("ticket.html");

        assertThat(html)
                .contains("id=\"openTask\"")
                .contains(">Task</button>")
                .contains("id=\"executeTicket\"")
                .contains(">Execute</button>")
                .contains("id=\"resetLayout\"")
                .contains(">Reset Layout</button>")
                .contains("id=\"refreshGraph\"")
                .contains(">Refresh</button>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorStaticPages_whenRendered_thenKeepTicketAndLaneFlows() {
        assertThat(OPERATOR_UI_DIR.resolve("new-task.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("lane.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("agents.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("services.html"))
                .exists();
        assertThat(OPERATOR_UI_DIR.resolve("service.html"))
                .exists();
    }

    @Test
    void givenLanePage_whenRendered_thenShowDependenciesInputsAndSession() throws Exception {
        final String html = this.read("lane.html");

        assertThat(html)
                .contains("id=\"stopLane\"")
                .contains(">Stop</button>")
                .contains("id=\"retryLane\"")
                .contains(">Retry</button>")
                .contains("id=\"laneDependencies\"")
                .contains(">Dependencies</h2>")
                .contains("id=\"laneInputs\"")
                .contains("id=\"laneEvents\"");
    }

    @Test
    void givenAgentsPage_whenRendered_thenUseSidebarNavigationAndRefreshAction() throws Exception {
        final String html = this.read("agents.html");

        assertThat(html)
                .contains("id=\"refreshAgents\"")
                .contains(">Refresh</button>")
                .contains("id=\"resourceContent\"")
                .contains("wrap=\"soft\"")
                .doesNotContain("href=\"./index.html\">Tickets</a>")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenServicesPage_whenRendered_thenExposeLocalServiceSanityUi() throws Exception {
        final String html = this.read("services.html");

        assertThat(html)
                .contains("data-page=\"services\"")
                .contains("id=\"refreshServices\"")
                .contains("id=\"operatorServicesList\"")
                .contains("services.yaml")
                .doesNotContain("id=\"operatorServiceDetail\"")
                .doesNotContain("local repository availability, branch sanity, docker state")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenKnowledgePage_whenRendered_thenExposeServiceStatusWorkflowWithoutRemovedPanels() throws Exception {
        final String html = this.read("knowledge.html");

        assertThat(html)
                .contains("data-page=\"knowledge\"")
                .contains("id=\"knowledgeSourcesBody\"")
                .contains("Catalog-driven file inventory, AI analysis coverage, and extracted facts.")
                .contains("<th>Service</th>")
                .contains("<th>Inventory</th>")
                .contains("<th>Analysis</th>")
                .contains("<th>Facts</th>")
                .contains("<th>Actions</th>")
                .doesNotContain("id=\"knowledgeStatusCards\"")
                .doesNotContain("id=\"knowledgeStatusMessage\"")
                .doesNotContain("id=\"knowledgeInventoryStatus\"")
                .doesNotContain("id=\"knowledgeFilesBody\"")
                .doesNotContain("Files Preview / Debug")
                .doesNotContain("<h2>Runtime</h2>")
                .doesNotContain("Keyword Search")
                .doesNotContain("Retrieval Context")
                .doesNotContain("Flow Context")
                .doesNotContain("Build Facts")
                .doesNotContain("Build Inventory")
                .doesNotContain("first indexed files");
    }

    @Test
    void givenKnowledgeGraphPage_whenRendered_thenExposeGraphWorkflow() throws Exception {
        final String html = this.read("knowledge-graph.html");

        assertThat(html)
                .contains("data-page=\"knowledge-graph\"")
                .contains("id=\"analyzeKnowledgeGraph\"")
                .contains("id=\"refreshKnowledgeGraph\"")
                .contains("id=\"focusKnowledgeGraph\"")
                .contains("id=\"knowledgeGraphAutoRefresh\"")
                .contains("id=\"knowledgeGraphProgress\"")
                .contains("id=\"knowledgeGraphMode\"")
                .contains("id=\"knowledgeGraphFlowDomain\"")
                .contains("id=\"knowledgeGraphDepth\"")
                .contains("id=\"knowledgeGraphDensity\"")
                .contains("id=\"knowledgeGraphLabelsMode\"")
                .contains("id=\"knowledgeGraphExternal\"")
                .contains("id=\"knowledgeGraphUnresolved\"")
                .contains("<option value=\"0\">Max</option>")
                .contains("id=\"knowledgeGraphIsolated\"")
                .contains("id=\"knowledgeGraphSearch\"")
                .contains("id=\"fitKnowledgeGraph\"")
                .contains("id=\"fitKnowledgeGraphTop\"")
                .contains("id=\"toggleKnowledgeGraphPanel\"")
                .contains("id=\"knowledgeGraphEmptyAction\"")
                .contains("id=\"knowledgeGraphSvg\"")
                .contains("id=\"knowledgeGraphPreview\"")
                .contains("data-graph-tab=\"overview\"")
                .contains("data-graph-tab=\"selected\"")
                .contains("data-graph-tab=\"nodes\"")
                .contains("data-graph-tab=\"edges\"")
                .contains("data-graph-tab=\"claims\"")
                .contains("data-graph-tab=\"diagnostics\"")
                .contains("id=\"knowledgeGraphDetails\"")
                .doesNotContain("id=\"analyzeKnowledgeGraphEmpty\"");
    }

    @Test
    void givenOperatorJs_whenRendered_thenKnowledgeUsesServiceStatusInventoryAnalysisAndFacts() throws Exception {
        final String js = this.read("operator-ui.js");

        assertThat(js)
                .doesNotContain("function renderKnowledgeStatus(")
                .doesNotContain("renderKnowledgeStatusCard")
                .doesNotContain("getInfrastructureJson('/knowledge/status')")
                .doesNotContain("getInfrastructureJson('/knowledge/inventory/status')")
                .doesNotContain("getInfrastructureJson('/knowledge/inventory/files")
                .doesNotContain("getInfrastructureJson(`/knowledge/analysis/jobs/")
                .doesNotContain("renderKnowledgeFiles")
                .doesNotContain("function renderKnowledgeFreshnessLabel(")
                .doesNotContain("Knowledge is outdated:")
                .doesNotContain("Analyze changed files")
                .doesNotContain("Refresh project state")
                .doesNotContain("Knowledge:")
                .doesNotContain("AI scan:")
                .doesNotContain("Legacy facts")
                .doesNotContain("Coverage: unknown")
                .doesNotContain("coverageKnown")
                .doesNotContain("indexed facts")
                .doesNotContain("Project changes since AI scan")
                .contains("getInfrastructureJson(knowledgeServicesStatusPath())")
                .contains("getInfrastructureJson(knowledgeServicesStatusPath(sourceId))")
                .contains("detailsSourceId=${encodeURIComponent(detailsSourceId)}")
                .doesNotContain("getInfrastructureJson(`/knowledge/analysis/symbols")
                .doesNotContain("getInfrastructureJson(`/knowledge/analysis/relations")
                .doesNotContain("getInfrastructureJson(`/knowledge/analysis/files")
                .contains("renderKnowledgeInventoryMini")
                .contains("renderKnowledgeAnalysisProgress")
                .contains("renderKnowledgeFactsCell")
                .contains("function knowledgeAnalysisMetrics(analysis)")
                .contains("completedOutcomes = analyzed + failed + skipped")
                .contains("pendingDerivedProcessed")
                .doesNotContain("const percent = Number(analysis?.percent)")
                .contains("symbols")
                .contains("relations")
                .contains("knowledge-source-stop-button")
                .contains("/knowledge/analysis/jobs/${encodeURIComponent(jobId)}/stop")
                .contains("pending ${escapeHtml(metrics.pending)}")
                .contains("failed ${escapeHtml(metrics.failed)}")
                .contains("knowledge-state-badge")
                .contains("<h3>Inventory</h3>")
                .contains("Not analyzed")
                .contains("RUNNING")
                .contains("lastProgressAt")
                .contains("knowledgeGraphUrl({ sourceId: source.sourceId")
                .contains("graphNodeId: symbol.symbolId")
                .contains("graphEdgeId: relation.relationId")
                .contains("renderKnowledgeGraphSourceContext")
                .contains("data.sourceStatus")
                .contains("data.failureFiles")
                .doesNotContain("knowledge-source-details-button")
                .doesNotContain("showKnowledgeServiceDetails")
                .doesNotContain("last analysis")
                .doesNotContain("0 indexed")
                .doesNotContain("const currentEligible")
                .doesNotContain("analysis.eligibleFilesAtScan ?? analysis.fileCount ?? currentEligible")
                .doesNotContain("postInfrastructureJson('/knowledge/inventory/build'");
    }

    @Test
    void givenOperatorJs_whenRendered_thenKnowledgeGraphUsesDedicatedEndpointAndPolling() throws Exception {
        final String js = this.read("operator-ui.js");

        assertThat(js)
                .contains("graphPollIntervalMs: 30000")
                .contains("const knowledgeGraphPollMs = Number(runtimeConfig.graphPollIntervalMs) || 30000")
                .contains("function loadKnowledgeGraph(")
                .contains("function renderKnowledgeGraphVisual(")
                .contains("function renderKnowledgeGraphDetails(")
                .contains("function scheduleKnowledgeGraphPolling(")
                .contains("function startKnowledgeGraphAnalysis(")
                .contains("postInfrastructureJson('/knowledge/analysis/build'")
                .contains("knowledgeGraphAnalysisRunning")
                .contains("Analysis running")
                .contains("toggleKnowledgeGraphFocus")
                .contains("preview-collapsed")
                .contains("renderKnowledgeGraphEmptyAction")
                .contains("knowledgeGraphEmptyState")
                .contains("knowledgeGraphHasFactsOutsideCurrentView")
                .contains("knowledgeGraphVisibleGraph")
                .contains("No graph items match current filters.")
                .contains("Use Analyze in the toolbar to build the graph.")
                .contains("unlimitedMax")
                .contains("query.set('maxEdges', unlimitedMax ? '0'")
                .contains("query.set('includeEvidence', 'false')")
                .contains("query.set('includeClaims', 'false')")
                .contains("query.set('includeDiagnostics', unlimitedMax ? 'false' : 'true')")
                .contains("loadKnowledgeGraphSelectedDetails")
                .contains("query.set('includeClaims', 'true')")
                .contains("Selected item details loaded on demand.")
                .contains("panKnowledgeGraphWithWheel")
                .contains("Graph truncated for readability.")
                .contains("Select a node, narrow filters, increase max, or switch to Full mode")
                .contains("includeIsolated")
                .contains("Showing connected overview.")
                .contains("isolated nodes are hidden")
                .contains("edges were hidden because their endpoint nodes were outside the current result")
                .contains("skippedMissingEndpointCount")
                .contains("skippedByLimitCount")
                .contains("truncationReason")
                .contains("edge.from")
                .contains("edge.to")
                .contains("node.id")
                .contains("getInfrastructureJson(endpoint)")
                .contains("/knowledge/analysis/graph/slice?")
                .contains("/knowledge/analysis/graph?")
                .contains("knowledgeGraphMissingRootError")
                .contains("allowMissingRootFallback")
                .contains("selectKnowledgeGraphNode")
                .contains("selectKnowledgeGraphEdge")
                .contains("if (page === 'knowledge-graph')")
                .contains("initKnowledgeGraphPage()");
    }

    @Test
    void givenOperatorStaticAssets_whenRendered_thenKnowledgeGraphShowsSummaryProvenanceAndConfidence() throws Exception {
        final String js = this.read("operator-ui.js");
        final String css = this.read("operator-ui.css");

        assertThat(js)
                .contains("summarySource")
                .contains("summaryClaimId")
                .contains("summaryClaimNodeId")
                .contains("summaryConfidence")
                .contains("summaryEvidenceCount")
                .contains("Direct responsibility")
                .contains("No direct method summary. Showing parent type summary.")
                .contains("No direct method summary. Showing file summary.")
                .contains("No direct responsibility summary for this node yet.")
                .contains("LOW CONFIDENCE")
                .contains("DEBUG ONLY")
                .contains("confidence-${knowledgeGraphConfidenceState(node)}")
                .contains("knowledge-graph-summary-block");

        assertThat(css)
                .contains(".knowledge-graph-summary-block")
                .contains(".knowledge-confidence-badge.low")
                .contains(".knowledge-confidence-badge.debug")
                .contains(".knowledge-graph-node.confidence-low")
                .contains(".knowledge-graph-node.confidence-debug")
                .contains("body.knowledge-graph-focus-mode")
                .contains("min-height: max(720px, calc(100vh - 260px))")
                .contains(".knowledge-graph-layout.preview-collapsed")
                .contains(".knowledge-graph-empty-action.hidden")
                .contains(".knowledge-graph-tabs")
                .contains("grid-template-columns: minmax(0, 1fr) 310px");
    }

    @Test
    void givenServiceDetailPage_whenRendered_thenExposeServiceDetailActions() throws Exception {
        final String html = this.read("service.html");

        assertThat(html)
                .contains("data-page=\"service\"")
                .contains("id=\"serviceDetailTitle\"")
                .contains("id=\"serviceDetailStatus\"")
                .contains("id=\"operatorServiceDetail\"")
                .contains("id=\"defaultServiceDialog\"")
                .contains("data-default-mode=\"COMMIT\"")
                .contains("data-default-mode=\"STASH\"")
                .doesNotContain("delete current branch")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorJs_whenRendered_thenKeepSidebarEntriesAndPageHandlers() throws Exception {
        final String js = this.read("operator-ui.js");

        assertThat(js)
                .contains("function initSidebar()")
                .contains("href=\"./index.html\"")
                .contains("<strong>Tickets</strong>")
                .contains("href=\"./new-task.html\"")
                .contains("<strong>New Task</strong>")
                .contains("href=\"./agents.html\"")
                .contains("<strong>Agents</strong>")
                .contains("href=\"./services.html\"")
                .contains("<strong>Services</strong>")
                .contains("href=\"../actuator/health\"")
                .contains("if (page === 'new-task')")
                .contains("if (page === 'lane')")
                .contains("if (page === 'agents')")
                .contains("if (page === 'services')")
                .contains("if (page === 'service')")
                .contains("loadOperatorServices")
                .contains("loadOperatorServiceDetail")
                .contains("groupOperatorServices")
                .contains("renderOperatorServiceGroup")
                .contains("serviceRuntimeVisible(service)")
                .contains("data-clone-service")
                .contains("data-default-service")
                .contains("data-default-mode")
                .contains("loadAgentsConfig")
                .contains("saveSelectedResource")
                .contains("formatEditableResourceContent")
                .contains("JSON.stringify(JSON.parse(content), null, 2)")
                .contains("const operatorApiBase =")
                .contains("function syncLaneStopButton(data)")
                .contains("function stopCurrentLaneExecution()")
                .contains("function syncLaneRetryButton(data)")
                .contains("function retryCurrentLaneExecution()")
                .contains("postOperatorJson(`/executions/${encodeURIComponent(executionId)}/interrupt`)")
                .contains("postOperatorJson(`/ui/tickets/${encodeURIComponent(ticketId)}/lanes/${encodeURIComponent(laneId)}/retry`)")
                .contains("renderLaneDependencies(data.dependencies || [])")
                .contains("function renderLaneEventMessage")
                .contains("jsonEventPreview")
                .contains("connectionColor(sourceStatus)")
                .contains("connectionMarkerId(sourceStatus)")
                .doesNotContain("class=\"side-nav\"")
                .doesNotContain("id=\"navToggle\"");
    }

    @Test
    void givenOperatorCss_whenRendered_thenKeepSidebarLayoutAndAgentsStyles() throws Exception {
        final String css = this.read("operator-ui.css");

        assertThat(css)
                .contains("--sidebar-width: 244px;")
                .contains("body.has-sidebar")
                .contains(".operator-sidebar")
                .contains(".sidebar-link")
                .contains(".shell {")
                .contains("margin: 0 auto;")
                .contains(".agents-grid")
                .contains(".agent-card")
                .contains(".services-grid")
                .contains(".operator-services-group")
                .contains(".operator-services-group-grid")
                .contains(".operator-service-card")
                .contains(".service-runtime-status")
                .contains(".service-clone-button")
                .contains(".button.danger")
                .contains(".contract-ref-card")
                .contains(".knowledge-graph-stage")
                .contains(".knowledge-graph-node")
                .contains(".knowledge-graph-edge")
                .contains(".knowledge-graph-detail-section")
                .contains("grid-template-columns: minmax(220px, 0.72fr) minmax(390px, 1.12fr) minmax(320px, 0.92fr);")
                .contains(".config-editor-panel")
                .contains(".dependency-card")
                .contains(".event-preview")
                .contains(".conversation-event .event-details")
                .contains("white-space: pre-wrap;")
                .contains("@media (max-width: 1280px)")
                .contains("@media (max-width: 1000px)")
                .doesNotContain(".side-nav")
                .doesNotContain(".nav-toggle")
                .doesNotContain("margin-left: 196px");
    }

    private String read(final String fileName) throws Exception {
        return Files.readString(OPERATOR_UI_DIR.resolve(fileName), StandardCharsets.UTF_8);
    }
}
