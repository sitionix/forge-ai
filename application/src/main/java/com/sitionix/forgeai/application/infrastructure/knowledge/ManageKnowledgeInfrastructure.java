package com.sitionix.forgeai.application.infrastructure.knowledge;

public interface ManageKnowledgeInfrastructure {

    KnowledgeStatusView status();

    KnowledgeSourcesView sources();

    KnowledgeInventoryBuildResultView buildInventory(KnowledgeInventoryBuildRequest request);

    KnowledgeInventoryStatusView inventoryStatus();

    KnowledgeFilesView files(KnowledgeFilesRequest request);

    KnowledgeSearchResultView search(KnowledgeSearchRequest request);

    KnowledgeContextView context(KnowledgeContextRequest request);
}
