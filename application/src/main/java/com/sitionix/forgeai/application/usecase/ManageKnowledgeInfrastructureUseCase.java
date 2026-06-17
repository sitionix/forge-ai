package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphSliceRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisGraphView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisJobView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServicesStatusView;
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
    public KnowledgeServicesStatusView servicesStatus() {
        return this.knowledgeGateway.servicesStatus();
    }

    @Override
    public KnowledgeServicesStatusView servicesStatus(final String detailsSourceId) {
        if (detailsSourceId == null || detailsSourceId.isBlank()) {
            return this.knowledgeGateway.servicesStatus();
        }
        return this.knowledgeGateway.servicesStatus(detailsSourceId);
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
    public KnowledgeAnalysisBuildView buildAnalysis(final KnowledgeAnalysisBuildRequest request) {
        return this.knowledgeGateway.buildAnalysis(request);
    }

    @Override
    public KnowledgeAnalysisJobView analysisJob(final String jobId) {
        return this.knowledgeGateway.analysisJob(jobId);
    }

    @Override
    public KnowledgeAnalysisStopView stopAnalysis(final String jobId) {
        return this.knowledgeGateway.stopAnalysis(jobId);
    }

    @Override
    public KnowledgeAnalysisStatusView analysisStatus() {
        return this.knowledgeGateway.analysisStatus();
    }

    @Override
    public KnowledgeAnalysisFilesView analysisFiles(final KnowledgeAnalysisFilesRequest request) {
        return this.knowledgeGateway.analysisFiles(request);
    }

    @Override
    public KnowledgeAnalysisSymbolsView analysisSymbols(final KnowledgeAnalysisSymbolsRequest request) {
        return this.knowledgeGateway.analysisSymbols(request);
    }

    @Override
    public KnowledgeAnalysisRelationsView analysisRelations(final KnowledgeAnalysisRelationsRequest request) {
        return this.knowledgeGateway.analysisRelations(request);
    }

    @Override
    public KnowledgeAnalysisGraphView analysisGraph(final KnowledgeAnalysisGraphRequest request) {
        return this.knowledgeGateway.analysisGraph(request);
    }

    @Override
    public KnowledgeAnalysisGraphView analysisGraphSlice(final KnowledgeAnalysisGraphSliceRequest request) {
        return this.knowledgeGateway.analysisGraphSlice(request);
    }
}
