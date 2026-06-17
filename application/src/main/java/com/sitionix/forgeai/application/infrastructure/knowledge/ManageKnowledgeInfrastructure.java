package com.sitionix.forgeai.application.infrastructure.knowledge;

public interface ManageKnowledgeInfrastructure {

    KnowledgeStatusView status();

    KnowledgeSourcesView sources();

    KnowledgeServicesStatusView servicesStatus();

    default KnowledgeServicesStatusView servicesStatus(final String detailsSourceId) {
        return this.servicesStatus();
    }

    KnowledgeInventoryBuildResultView buildInventory(KnowledgeInventoryBuildRequest request);

    KnowledgeInventoryStatusView inventoryStatus();

    KnowledgeFilesView files(KnowledgeFilesRequest request);

    KnowledgeAnalysisBuildView buildAnalysis(KnowledgeAnalysisBuildRequest request);

    KnowledgeAnalysisJobView analysisJob(String jobId);

    KnowledgeAnalysisStopView stopAnalysis(String jobId);

    KnowledgeAnalysisStatusView analysisStatus();

    KnowledgeAnalysisFilesView analysisFiles(KnowledgeAnalysisFilesRequest request);

    KnowledgeAnalysisSymbolsView analysisSymbols(KnowledgeAnalysisSymbolsRequest request);

    KnowledgeAnalysisRelationsView analysisRelations(KnowledgeAnalysisRelationsRequest request);
}
