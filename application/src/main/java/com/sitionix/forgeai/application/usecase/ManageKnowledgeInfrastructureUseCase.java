package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.ManageKnowledgeInfrastructure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageKnowledgeInfrastructureUseCase implements ManageKnowledgeInfrastructure {

    private final KnowledgeGateway knowledgeGateway;

    @Override
    public KnowledgeStatusView status() {
        return this.knowledgeGateway.status();
    }

    @Override
    public KnowledgeSourcesView sources() {
        return this.knowledgeGateway.sources();
    }

    @Override
    public KnowledgeInventoryBuildResultView buildInventory(final KnowledgeInventoryBuildRequest request) {
        return this.knowledgeGateway.buildInventory(request);
    }

    @Override
    public KnowledgeInventoryStatusView inventoryStatus() {
        return this.knowledgeGateway.inventoryStatus();
    }

    @Override
    public KnowledgeFilesView files(final KnowledgeFilesRequest request) {
        return this.knowledgeGateway.files(request);
    }

    @Override
    public KnowledgeSearchResultView search(final KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID, "Search query must not be empty");
        }
        return this.knowledgeGateway.search(request);
    }

    @Override
    public KnowledgeContextView context(final KnowledgeContextRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.CONTEXT_QUERY_INVALID, "Context query must not be empty");
        }
        return this.knowledgeGateway.context(request);
    }
}
