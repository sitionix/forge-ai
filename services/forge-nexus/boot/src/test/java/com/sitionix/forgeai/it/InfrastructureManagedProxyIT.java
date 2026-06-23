package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.InfrastructureProxyEndpoint;
import com.sitionix.forgeai.it.infra.InfrastructureProxyAsyncMockMvc;
import com.sitionix.forgeai.it.infra.InfrastructureProxyFixtures;
import com.sitionix.forgeai.it.infra.InfrastructureProxyQuery;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
        "forge.ai.infrastructure.proxy.max-request-body-bytes=128",
        "forge.ai.infrastructure.proxy.max-response-body-bytes=1024"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class InfrastructureManagedProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Autowired
    private InfrastructureProxyAsyncMockMvc proxyMockMvc;

    @BeforeEach
    void setUpForgeFixtures() {
        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubCommonKnowledgeRoutes(this.testManager);
        InfrastructureProxyFixtures.stubCommonJarvisRoutes(this.testManager);
        InfrastructureProxyFixtures.stubProxyErrorRoutes(this.testManager);
        InfrastructureProxyFixtures.stubFinalGraphRoutes(this.testManager);
    }

    @Test
    void itProxy01RouteAllowlistForwardsActiveRoutesWithExactContracts() {
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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifest()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChat()).header("X-Correlation-Id", "corr-allowlist").assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeSymbols()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeRelations()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeGraph()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusUnsupportedKnowledgeGraphSlice()).assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusOpenProxyRejected()).assertDefault();
    }

    @Test
    void itProxy02RawJsonPreservationKeepsRepresentativeBodies() {
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeOverview()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifest()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChat()).header("X-Correlation-Id", "corr-raw-json").assertDefault();
    }

    @Test
    void itProxy03QueryPathAndBodyParityIsCapturedByUpstreamContracts() {
        InfrastructureProxyFixtures.stubPostBodyRoutes(this.testManager);

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

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisCommand()).header("X-Correlation-Id", "corr-parity").assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChat()).header("X-Correlation-Id", "corr-parity").assertDefault();
    }

    @Test
    void itProxy04StructuredErrorMappingUsesOneEnvelope() {
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusTimeout())
                .withQueryParameters(InfrastructureProxyQuery.timeoutCase())
                .header("X-Correlation-Id", "corr-timeout")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-timeout"))
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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphSnapshotExpired())
                .withQueryParameters(InfrastructureProxyQuery.graphExpiredRevision())
                .header("X-Correlation-Id", "corr-upstream-410")
                .assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuildRequestTooLarge())
                .header("X-Correlation-Id", "corr-knowledge-request-large")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusOpenProxyRejected()).assertDefault();
    }

    @Test
    void itProxy05CorrelationIdPropagationIsPreservedGeneratedAndRejectedWhenUnsafe() {
        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubGeneratedCorrelationRoute(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisActions())
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", matchesPattern("[A-Za-z0-9._:-]{1,128}")))
                .assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubCorrelationRoute(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisStatus())
                .header("X-Correlation-Id", "corr-123")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-123"))
                .assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubSafeKnowledgeCorrelationRoute(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatus())
                .header("X-Correlation-Id", "unsafe header with spaces")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", not("unsafe header with spaces")))
                .assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubProxyErrorRoutes(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusTimeout())
                .withQueryParameters(InfrastructureProxyQuery.timeoutCase())
                .header("X-Correlation-Id", "corr-timeout")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-timeout"))
                .assertDefault();
    }

    @Test
    void itProxy06NonBlockingSaturationKeepsHealthAndFastRoutesResponsive() throws Exception {
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
        InfrastructureProxyFixtures.reset(this.testManager);
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeAnalysisBuildRequestTooLarge())
                .header("X-Correlation-Id", "corr-knowledge-request-large")
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChatRequestTooLarge())
                .header("X-Correlation-Id", "corr-jarvis-request-large")
                .assertDefault();

        InfrastructureProxyFixtures.reset(this.testManager);
        InfrastructureProxyFixtures.stubProxyErrorRoutes(this.testManager);
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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifestFiltered())
                .withQueryParameters(InfrastructureProxyQuery.graphFilteredManifest())
                .header("X-Correlation-Id", "corr-graph-parity")
                .assertDefault();
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphManifestQuery())
                .withQueryParameters(InfrastructureProxyQuery.graphManifestCode())
                .header("X-Correlation-Id", "corr-graph-parity")
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
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeGraphSnapshotExpired())
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
    void itProxy10JarvisRedactionThroughNexusDoesNotExposeSensitiveDetails() {
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChat())
                .header("X-Correlation-Id", "corr-jarvis-redaction")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.usedContext").isEmpty())
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("SYSTEM PROMPT"))))
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("source content"))))
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("127.0.0.1"))))
                .assertDefault();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisChatUpstreamServerError())
                .withQueryParameters(InfrastructureProxyQuery.serverErrorCase())
                .header("X-Correlation-Id", "corr-jarvis-chat-error")
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
