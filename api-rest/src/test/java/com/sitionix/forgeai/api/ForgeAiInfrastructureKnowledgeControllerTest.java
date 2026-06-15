package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeViews;
import com.sitionix.forgeai.application.infrastructure.knowledge.ManageKnowledgeInfrastructure;
import org.junit.jupiter.api.Test;

class ForgeAiInfrastructureKnowledgeControllerTest {

    @Test
    void statusDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        when(useCase.status()).thenReturn(new KnowledgeStatusView(
                "UP",
                "knowledge",
                null,
                null,
                null,
                new KnowledgeViews.KnowledgeCoverageView(100, 100, "2026-06-14T10:00:00Z"),
                new KnowledgeViews.KnowledgeFreshnessView("UP_TO_DATE", "2026-06-14T10:01:00Z", 0, 0, 0, 0),
                null
        ));
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        final var response = controller.status();

        assertThat(response.getBody().status()).isEqualTo("UP");
    }

    @Test
    void analysisBuildDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final KnowledgeAnalysisBuildRequest request = new KnowledgeAnalysisBuildRequest(java.util.List.of("svc"), java.util.List.of(), false, 5, 1);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.buildAnalysis(request);

        org.mockito.Mockito.verify(useCase).buildAnalysis(request);
    }

    @Test
    void analysisJobDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.analysisJob("job-1");

        org.mockito.Mockito.verify(useCase).analysisJob("job-1");
    }

    @Test
    void analysisStopDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.stopAnalysis("job-1");

        org.mockito.Mockito.verify(useCase).stopAnalysis("job-1");
    }

    @Test
    void analysisStatusDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.analysisStatus();

        org.mockito.Mockito.verify(useCase).analysisStatus();
    }

    @Test
    void servicesStatusDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.servicesStatus();

        org.mockito.Mockito.verify(useCase).servicesStatus();
    }

    @Test
    void analysisFilesDelegatesQueryParamsToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.analysisFiles("svc", "ANALYZED", "path", 10, 1);

        org.mockito.Mockito.verify(useCase).analysisFiles(new KnowledgeAnalysisFilesRequest("svc", "ANALYZED", "path", 10, 1));
    }

    @Test
    void analysisSymbolsDelegatesQueryParamsToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.analysisSymbols("svc", "HTTP_HANDLER", "CLASS", "path", "name", 10, 1);

        org.mockito.Mockito.verify(useCase).analysisSymbols(new KnowledgeAnalysisSymbolsRequest("svc", "HTTP_HANDLER", "CLASS", "path", "name", 10, 1));
    }

    @Test
    void analysisRelationsDelegatesQueryParamsToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.analysisRelations("svc", "CALLS", "from", "to", 10, 1);

        org.mockito.Mockito.verify(useCase).analysisRelations(new KnowledgeAnalysisRelationsRequest("svc", "CALLS", "from", "to", 10, 1));
    }

    @Test
    void knowledgeUnavailableMapsControlledError() {
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(mock(ManageKnowledgeInfrastructure.class));

        final var response = controller.handleKnowledgeGatewayException(
                new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE, "Knowledge is unavailable"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("KNOWLEDGE_UNAVAILABLE");
    }

    @Test
    void knowledgeNotFoundMapsControlledError() {
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(mock(ManageKnowledgeInfrastructure.class));

        final var response = controller.handleKnowledgeGatewayException(
                new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_NOT_FOUND, "ANALYSIS_JOB_NOT_FOUND", "Analysis job not found"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("ANALYSIS_JOB_NOT_FOUND");
    }

    @Test
    void knowledgeConflictMapsControlledError() {
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(mock(ManageKnowledgeInfrastructure.class));

        final var response = controller.handleKnowledgeGatewayException(
                new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_CONFLICT, "ANALYSIS_JOB_ALREADY_RUNNING", "Knowledge analysis job already running"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("ANALYSIS_JOB_ALREADY_RUNNING");
    }

}
