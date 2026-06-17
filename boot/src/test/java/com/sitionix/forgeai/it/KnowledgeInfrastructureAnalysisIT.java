package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphMetaView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeDiagnosticView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceAnalysisView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceFactsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceInventoryView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServicesStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSkippedBreakdownView;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class KnowledgeInfrastructureAnalysisIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private KnowledgeGateway knowledgeGateway;

    @Test
    @DisplayName("Should proxy Knowledge service status through Forge infrastructure API")
    void givenKnowledgeServicesStatus_whenGetServicesStatus_thenReturnTypedServiceSnapshot() throws Exception {
        when(this.knowledgeGateway.servicesStatus()).thenReturn(new KnowledgeServicesStatusView(
                List.of(new KnowledgeServiceStatusView(
                        "svc",
                        "Service",
                        "Service",
                        "backend",
                        "svc",
                        true,
                        List.of("java", "spring-boot"),
                        new KnowledgeServiceInventoryView(
                                "READY",
                                2,
                                1,
                                new KnowledgeSkippedBreakdownView(1, Map.of("EXCLUDED_BY_PATTERN", 1)),
                                "2026-06-15T07:00:00Z"
                        ),
                        new KnowledgeServiceAnalysisView(
                                "PARTIAL",
                                2,
                                1,
                                50.0,
                                1,
                                1,
                                0,
                                0,
                                0,
                                null,
                                "2026-06-15T07:01:00Z",
                                null
                        ),
                        new KnowledgeServiceFactsView(3, 4),
                        List.of(new KnowledgeDiagnosticView(
                                "ANALYSIS_AI_INVALID_JSON",
                                "AI analyzer returned invalid JSON",
                                "svc",
                                "src/App.java",
                                2,
                                "{bad",
                                1,
                                List.of("src/App.java")
                        ))
                )),
                null
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeServicesStatus())
                .assertDefault();

        verify(this.knowledgeGateway).servicesStatus();
    }

    @Test
    @DisplayName("Should proxy Knowledge analysis graph through Forge infrastructure API")
    void givenKnowledgeGraph_whenGetAnalysisGraph_thenReturnGraphProjection() throws Exception {
        final Map<String, Object> selected = new HashMap<>();
        selected.put("node", null);
        selected.put("edge", null);
        final KnowledgeAnalysisGraphMetaView meta = new KnowledgeAnalysisGraphMetaView(false, 1, 3, 1, 0, 500, 1000);
        meta.skippedEdgeCount(3);
        meta.skippedMissingEndpointCount(2);
        meta.skippedByLimitCount(1);
        meta.truncationReason("NODE_LIMIT,EDGE_ENDPOINT_NOT_RETURNED");
        meta.setAdditionalProperty("futureProjectionMetric", 42);
        when(this.knowledgeGateway.analysisGraph(new KnowledgeAnalysisGraphRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null
        ))).thenReturn(new KnowledgeAnalysisGraphView(
                "svc",
                "Service",
                new KnowledgeAnalysisGraphStatusView(
                        "READY",
                        "job-1",
                        "GRAPH_V1",
                        2,
                        2,
                        0,
                        100.0,
                        null,
                        3,
                        0,
                        "2026-06-15T07:02:00Z"
                ),
                Map.of("depth", 2),
                List.of(Map.of("id", "n1", "label", "Handler", "nodeKind", "CALLABLE")),
                List.of(),
                List.of(),
                List.of(),
                selected,
                List.of(),
                List.of(),
                List.of(),
                Map.of("sliceNodeCount", 1),
                meta
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeAnalysisGraph())
                .assertDefault();

        verify(this.knowledgeGateway).analysisGraph(new KnowledgeAnalysisGraphRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null
        ));
    }

    @Test
    @DisplayName("Should proxy Knowledge analysis stop through Forge infrastructure API")
    void givenRunningKnowledgeAnalysisJob_whenStopAnalysis_thenReturnStopRequested() throws Exception {
        final String jobId = "3483cd96-37f6-4156-826e-59fc4320d826";
        when(this.knowledgeGateway.stopAnalysis(jobId)).thenReturn(new KnowledgeAnalysisStopView(
                jobId,
                "STOP_REQUESTED",
                "Knowledge analysis stop requested"
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeAnalysisStop())
                .withPathParameters(PathParams.create().add("jobId", jobId))
                .assertDefault();

        verify(this.knowledgeGateway).stopAnalysis(jobId);
    }
}
