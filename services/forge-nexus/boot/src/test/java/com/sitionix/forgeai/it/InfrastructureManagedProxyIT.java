package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.InfrastructureProxyEndpoint;
import com.sitionix.forgeai.it.infra.InfrastructureProxyAsyncMockMvc;
import com.sitionix.forgeai.it.infra.InfrastructureProxyQuery;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.wiremock.api.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.knowledge.read-timeout=2500ms",
        "forge.ai.infrastructure.jarvis.read-timeout=2500ms",
        "forge.ai.query.human-query.request-timeout=1000ms",
        "forge.ai.infrastructure.proxy.max-request-body-bytes=128",
        "forge.ai.infrastructure.proxy.max-response-body-bytes=6500"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class InfrastructureManagedProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Autowired
    private InfrastructureProxyAsyncMockMvc proxyMockMvc;

    @Test
    void itProxy01RouteAllowlistForwardsActiveRoutesWithExactContracts() {
        //given
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeSources()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeOverview()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryBuild()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryStatus()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryFiles())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamInventoryJavaExtension())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInventoryFiles.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisBuild()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisRetryFailed()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJob()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJobStop()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisStatus()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisFiles()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisDiagnostics()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMetadata())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphMetadataSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphMetadata.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifest()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphView()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodes())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodes.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdges())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdges.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetail.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetail.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisActions()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommand()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery()).createDefault();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatus()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeSources()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeOverview()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeInventoryBuild()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeInventoryStatus()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeInventoryFiles())
                .withQueryParameters(InfrastructureProxyQuery.inventoryJavaExtension())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuild()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisRetryFailed()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisJob()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisJobStop()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisStatus()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisFiles()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisDiagnostics()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphMetadata())
                .withQueryParameters(InfrastructureProxyQuery.graphMetadataSource())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifest()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphView()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodes())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdges())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNode())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdge())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-allowlist")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisActions()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisCommand()).header("X-Correlation-Id", "corr-allowlist").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeGraph()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeGraphSlice()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusOpenProxyRejected()).assertDefault();
    }

    @Test
    void itProxy02RawJsonPreservationKeepsRepresentativeBodies() {
        //given
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeOverview()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMetadata())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphMetadataSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphMetadata.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifest()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphView()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodes())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodes.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdges())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdges.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetail.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetail.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisStatus()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisActions()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommand()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery()).createDefault();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeOverview()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphMetadata())
                .withQueryParameters(InfrastructureProxyQuery.graphMetadataSource())
                .header("X-Correlation-Id", "corr-raw-json")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifest()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphView()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodes())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-raw-json")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdges())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-raw-json")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNode())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-raw-json")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdge())
                .withQueryParameters(InfrastructureProxyQuery.graphRevisionA())
                .header("X-Correlation-Id", "corr-raw-json")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisStatus()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisActions()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisCommand()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
    }

    @Test
    void itProxy03QueryPathAndBodyParityIsCapturedByUpstreamContracts() {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryBuildBody())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTraceOne())
                .applyDefault(context -> context
                        .matchesJson("requestProxyInventoryBuild.json")
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInventoryBuild.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisBuildBody()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisRetryFailed()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJobStopBody()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestFiltered())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphFilteredManifest())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestFiltered.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphView())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphViewContract())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphView.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphViewFilterInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphViewInvalidFilter())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.BAD_REQUEST.value())
                        .responseBody("responseProxyGraphViewFilterInvalid.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodesContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphEdgeCalls())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgesContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetailContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetailContract.json"))
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeQuery()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeQueryToolContext()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommand()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery()).createDefault();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeInventoryBuildBody())
                .withQueryParameters(InfrastructureProxyQuery.traceOne())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuildBody()).header("X-Correlation-Id", "corr-parity").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisRetryFailed()).header("X-Correlation-Id", "corr-parity").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisJobStopBody()).header("X-Correlation-Id", "corr-parity").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifestFiltered())
                .withQueryParameters(InfrastructureProxyQuery.graphFilteredManifest())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphView())
                .withQueryParameters(InfrastructureProxyQuery.graphViewContract())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodesContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodesContract())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdgesContract())
                .withQueryParameters(InfrastructureProxyQuery.graphEdgesContract())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodeContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodeDetailContract())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdgeContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodeDetailContract())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeQuery())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeQueryToolContext())
                .header("X-Correlation-Id", "corr-parity")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisCommand()).header("X-Correlation-Id", "corr-parity").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery()).header("X-Correlation-Id", "corr-parity").assertDefault();
    }

    @Test
    void itProxy04StructuredErrorMappingUsesOneEnvelope() {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTimeoutCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(3000)
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamTooLargeKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamResponseTooLargeCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyTooLarge.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatusNonJson())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamNonJsonCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInvalidJson.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatusServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphCursorInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorMalformed())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.BAD_REQUEST.value())
                        .responseBody("responseProxyGraphCursorInvalid.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphNodeNotFound.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphRevisionStale())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphExpiredRevision())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.GONE.value())
                        .responseBody("responseProxyGraphRevisionStale.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryTimeout())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTimeoutCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisQuery.json"))
                .delayForResponse(3000)
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusTimeout())
                .withQueryParameters(InfrastructureProxyQuery.timeoutCase())
                .header("X-Correlation-Id", "corr-timeout")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-timeout"))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryTimeout())
                .withQueryParameters(InfrastructureProxyQuery.timeoutCase())
                .header("X-Correlation-Id", "corr-jarvis-query-timeout")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-jarvis-query-timeout"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.route").value("jarvis.query"))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusResponseTooLarge())
                .withQueryParameters(InfrastructureProxyQuery.responseTooLargeCase())
                .header("X-Correlation-Id", "corr-response-large")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("xxxx"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusNonJson())
                .withQueryParameters(InfrastructureProxyQuery.nonJsonCase())
                .header("X-Correlation-Id", "corr-non-json")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusUpstreamServerError())
                .withQueryParameters(InfrastructureProxyQuery.serverErrorCase())
                .header("X-Correlation-Id", "corr-upstream-500")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("Traceback"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("/home/user"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("SYSTEM PROMPT"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("source content"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("127.0.0.1"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphCursorInvalid())
                .withQueryParameters(InfrastructureProxyQuery.graphCursorInvalid())
                .header("X-Correlation-Id", "corr-upstream-400")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphMissingNode())
                .withQueryParameters(InfrastructureProxyQuery.graphMissingDetail())
                .header("X-Correlation-Id", "corr-upstream-404")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphRevisionStale())
                .withQueryParameters(InfrastructureProxyQuery.graphExpiredRevision())
                .header("X-Correlation-Id", "corr-upstream-410")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuildRequestTooLarge())
                .header("X-Correlation-Id", "corr-knowledge-request-large")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusOpenProxyRejected()).assertDefault();
    }

    @Test
    void itProxy05CorrelationIdPropagationIsPreservedGeneratedAndRejectedWhenUnsafe() {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisActions())
                .plainUrl()
                .header("X-Correlation-Id", Parameter.matches("[A-Za-z0-9._:-]{1,128}"))
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisActions.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisActions())
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", matchesPattern("[A-Za-z0-9._:-]{1,128}")))
                .assertDefault();

        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus())
                .plainUrl()
                .header("X-Correlation-Id", "corr-123")
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisStatus.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus())
                .header("X-Correlation-Id", "corr-123")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-123"))
                .assertDefault();

        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .plainUrl()
                .header("X-Correlation-Id", Parameter.matches("[A-Za-z0-9._:-]{1,128}"))
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatus())
                .header("X-Correlation-Id", "unsafe header with spaces")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", not("unsafe header with spaces")))
                .assertDefault();

        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTimeoutCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(3000)
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusTimeout())
                .withQueryParameters(InfrastructureProxyQuery.timeoutCase())
                .header("X-Correlation-Id", "corr-timeout")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-timeout"))
                .assertDefault();
    }

    @Test
    void itProxy05bCorrelationIdPropagatesThroughKnowledgeAndJarvisProxyBoundary() {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeOverview())
                .plainUrl()
                .header("X-Correlation-Id", "corr-task04b-knowledge-overview")
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeOverview.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifest())
                .plainUrl()
                .header("X-Correlation-Id", "corr-task04b-graph-manifest")
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifest.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .header("X-Correlation-Id", "corr-jarvis-query-error")
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeOverview())
                .header("X-Correlation-Id", "corr-task04b-knowledge-overview")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-task04b-knowledge-overview"))
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifest())
                .header("X-Correlation-Id", "corr-task04b-graph-manifest")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-task04b-graph-manifest"))
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryUpstreamServerError())
                .withQueryParameters(InfrastructureProxyQuery.serverErrorCase())
                .header("X-Correlation-Id", "corr-jarvis-query-error")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-jarvis-query-error"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.correlationId").value("corr-jarvis-query-error"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("SYSTEM PROMPT"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("source content"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("127.0.0.1"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("/home/user"))))
                .assertDefault();

        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeOverview())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTraceOne())
                .header("X-Correlation-Id", Parameter.matches("[A-Za-z0-9._:-]{1,128}"))
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeOverview.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeOverview())
                .withQueryParameters(InfrastructureProxyQuery.traceOne())
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", matchesPattern("[A-Za-z0-9._:-]{1,128}")))
                .assertDefault();
    }

    @Test
    void itProxy06NonBlockingSaturationKeepsHealthAndFastRoutesResponsive() throws Exception {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamSlowCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(500)
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeSources()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus()).createDefault();
        final ExecutorService executor = Executors.newFixedThreadPool(50);
        final List<Callable<Void>> slowCalls = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            slowCalls.add(() -> {
                this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatus())
                        .withQueryParameters(InfrastructureProxyQuery.slowCase())
                        .header("X-Correlation-Id", "corr-slow")
                        .assertDefault();
                return null;
            });
        }

        try {
            final var futures = slowCalls.stream().map(executor::submit).toList();
            TimeUnit.MILLISECONDS.sleep(75);

            this.proxyMockMvc.ping(InfrastructureProxyEndpoint.actuatorHealth()).assertDefault();
            this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeSources()).header("X-Correlation-Id", "corr-fast-knowledge").assertDefault();
            this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus()).header("X-Correlation-Id", "corr-fast-jarvis").assertDefault();

            for (final var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void itProxy07ClientCancellationLeavesFollowingFastRequestHealthy() throws Exception {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamSlowCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(500)
                .create();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus()).createDefault();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final var slowCall = executor.submit(() -> {
                this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatus())
                        .withQueryParameters(InfrastructureProxyQuery.slowCase())
                        .header("X-Correlation-Id", "corr-cancel")
                        .assertDefault();
            });
            TimeUnit.MILLISECONDS.sleep(50);

            assertThat(slowCall.cancel(true)).isTrue();
            this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus())
                    .header("X-Correlation-Id", "corr-cancel-fast")
                    .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-cancel-fast"))
                    .assertDefault();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void itProxy08RequestAndResponseBodyLimitsAreStructuredForKnowledgeAndJarvis() {
        //given

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuildRequestTooLarge())
                .header("X-Correlation-Id", "corr-knowledge-request-large")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryRequestTooLarge())
                .header("X-Correlation-Id", "corr-jarvis-request-large")
                .assertDefault();

        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamTooLargeKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamResponseTooLargeCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyTooLarge.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamTooLargeJarvisStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamResponseTooLargeCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyTooLarge.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusResponseTooLarge())
                .withQueryParameters(InfrastructureProxyQuery.responseTooLargeCase())
                .header("X-Correlation-Id", "corr-response-large")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("xxxx"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatusResponseTooLarge())
                .withQueryParameters(InfrastructureProxyQuery.responseTooLargeCase())
                .header("X-Correlation-Id", "corr-jarvis-response-large")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("xxxx"))))
                .assertDefault();
    }

    @Test
    void itProxy09FinalGraphContractParityPreservesKnowledgeStatusAndBodies() {
        //given
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestQuery())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphManifestCode())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestQuery.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestFiltered())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphFilteredManifest())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestFiltered.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphView())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphViewContract())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphView.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphViewFilterInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphViewInvalidFilter())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.BAD_REQUEST.value())
                        .responseBody("responseProxyGraphViewFilterInvalid.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodesContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphEdgeCalls())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgesContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetailContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetailContract.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphPageSizeInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphPageSizeZero())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.UNPROCESSABLE_ENTITY.value())
                        .responseBody("responseProxyGraphPageSizeInvalid.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphCursorInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorMalformed())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.BAD_REQUEST.value())
                        .responseBody("responseProxyGraphCursorInvalid.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphRevisionStale())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphExpiredRevision())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.GONE.value())
                        .responseBody("responseProxyGraphRevisionStale.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphNodeNotFound.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphEdgeNotFound.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifestFiltered())
                .withQueryParameters(InfrastructureProxyQuery.graphFilteredManifest())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifestQuery())
                .withQueryParameters(InfrastructureProxyQuery.graphManifestCode())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphView())
                .withQueryParameters(InfrastructureProxyQuery.graphViewContract())
                .header("X-Correlation-Id", "corr-graph-parity")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.selectionPolicy").value("RELATIONSHIP_AWARE"))
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphViewFilterInvalid())
                .withQueryParameters(InfrastructureProxyQuery.graphViewInvalidFilter())
                .header("X-Correlation-Id", "corr-graph-parity")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.code").value("GRAPH_FILTER_INVALID"))
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodesContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodesContract())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdgesContract())
                .withQueryParameters(InfrastructureProxyQuery.graphEdgesContract())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphNodeContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodeDetailContract())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphEdgeContract())
                .withQueryParameters(InfrastructureProxyQuery.graphNodeDetailContract())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphPageSizeInvalid())
                .withQueryParameters(InfrastructureProxyQuery.graphPageSizeInvalid())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphCursorInvalid())
                .withQueryParameters(InfrastructureProxyQuery.graphCursorInvalid())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphRevisionStale())
                .withQueryParameters(InfrastructureProxyQuery.graphExpiredRevision())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphMissingNode())
                .withQueryParameters(InfrastructureProxyQuery.graphMissingDetail())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphMissingEdge())
                .withQueryParameters(InfrastructureProxyQuery.graphMissingDetail())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
    }

    @Test
    void itProxy10JarvisQueryPositiveHumanContractPreservesFields() {
        //given
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryOptionalControls()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryNoCandidates()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryGerman()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryFrench()).createDefault();
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryForbiddenLanguage()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryHumanTimeout())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamHumanTimeoutCase())
                .createDefault();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery())
                .header("X-Correlation-Id", "corr-jarvis-query-positive")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answerLanguage").value("uk"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].graphId").value("graph-jarvis-gateway"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].sources[0]").value("source-a"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].queryEntries[0].unitId").value("unit-jarvis-gateway"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].queryEntries[0].root.qualifiedName").value("JarvisGateway"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.diagnostics").isEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answer").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.sources").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.flows").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.flowExplanations").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.matchedNodes").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("nodeRef"))))
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("transitionRef"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryOptionalControls())
                .header("X-Correlation-Id", "corr-jarvis-query-optional")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").doesNotExist())
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryGerman())
                .header("X-Correlation-Id", "corr-jarvis-query-de")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answerLanguage").value("de"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryFrench())
                .header("X-Correlation-Id", "corr-jarvis-query-fr")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answerLanguage").value("fr"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryForbiddenLanguage())
                .header("X-Correlation-Id", "corr-jarvis-query-ru")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.code").value("RESPONSE_LANGUAGE_NOT_ALLOWED"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message").value("The requested response language is not allowed."))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.correlationId").doesNotExist())
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryNoCandidates())
                .header("X-Correlation-Id", "corr-jarvis-query-no-candidates")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.code").value("NO_GROUNDED_GRAPH_CANDIDATES"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message").value("No grounded graph candidates were found."))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.flows").doesNotExist())
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryHumanTimeout())
                .withQueryParameters(InfrastructureProxyQuery.humanTimeoutCase())
                .header("X-Correlation-Id", "corr-jarvis-human-timeout")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.code").value("HUMAN_QUERY_TIMEOUT"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message").value("Knowledge human query timed out."))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.correlationId").value("corr-jarvis-human-timeout"))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryBlank())
                .header("X-Correlation-Id", "corr-jarvis-query-blank")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.title").value("VALIDATION_FAILED"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.details", containsString("queryText")))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryOldRequest())
                .header("X-Correlation-Id", "corr-jarvis-query-old")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.title").value("VALIDATION_FAILED"))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryUnknownField())
                .header("X-Correlation-Id", "corr-jarvis-query-unknown-field")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.title").value("VALIDATION_FAILED"))
                .assertDefault();
    }

    @Test
    void itProxy11JarvisRedactionThroughNexusDoesNotExposeSensitiveDetails() {
        //given
        this.testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery()).createDefault();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisQueryServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommandServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();

        //when then
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery())
                .header("X-Correlation-Id", "corr-jarvis-redaction")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.matchedNodes").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("SYSTEM PROMPT"))))
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("source content"))))
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("127.0.0.1"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQueryUpstreamServerError())
                .withQueryParameters(InfrastructureProxyQuery.serverErrorCase())
                .header("X-Correlation-Id", "corr-jarvis-query-error")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("SYSTEM PROMPT"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("source content"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("127.0.0.1"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisCommandUpstreamServerError())
                .withQueryParameters(InfrastructureProxyQuery.serverErrorCase())
                .header("X-Correlation-Id", "corr-jarvis-command-error")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("Traceback"))))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.message", not(containsString("/home/user"))))
                .assertDefault();
    }
}
