package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.ManageKnowledgeInfrastructure;
import org.junit.jupiter.api.Test;

class ForgeAiInfrastructureKnowledgeControllerTest {

    @Test
    void statusDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        when(useCase.status()).thenReturn(new KnowledgeStatusView("UP", "knowledge", null, null, null, null, null, null));
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        final var response = controller.status();

        assertThat(response.getBody().status()).isEqualTo("UP");
    }

    @Test
    void searchDelegatesToUseCase() {
        final ManageKnowledgeInfrastructure useCase = mock(ManageKnowledgeInfrastructure.class);
        final KnowledgeSearchRequest request = new KnowledgeSearchRequest("query", java.util.List.of(), java.util.List.of(), 10);
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(useCase);

        controller.search(request);

        org.mockito.Mockito.verify(useCase).search(request);
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
    void searchValidationMapsBadRequest() {
        final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(mock(ManageKnowledgeInfrastructure.class));

        final var response = controller.handleKnowledgeGatewayException(
                new KnowledgeGatewayException(KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID, "Search query must not be empty"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
