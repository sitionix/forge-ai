package com.sitionix.forgeai.api;

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
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServicesStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.ManageKnowledgeInfrastructure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiInfrastructureKnowledgeController {

    private final ManageKnowledgeInfrastructure manageKnowledgeInfrastructure;

    @GetMapping("/api/v1/infrastructure/knowledge/status")
    public ResponseEntity<KnowledgeStatusView> status() {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.status());
    }

    @GetMapping("/api/v1/infrastructure/knowledge/sources")
    public ResponseEntity<KnowledgeSourcesView> sources() {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.sources());
    }

    @GetMapping("/api/v1/infrastructure/knowledge/services/status")
    public ResponseEntity<KnowledgeServicesStatusView> servicesStatus(@RequestParam(required = false) final String detailsSourceId) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.servicesStatus(detailsSourceId));
    }

    @PostMapping("/api/v1/infrastructure/knowledge/inventory/build")
    public ResponseEntity<KnowledgeInventoryBuildResultView> buildInventory(@RequestBody(required = false) final KnowledgeInventoryBuildRequest request) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.buildInventory(request));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/inventory/status")
    public ResponseEntity<KnowledgeInventoryStatusView> inventoryStatus() {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.inventoryStatus());
    }

    @GetMapping("/api/v1/infrastructure/knowledge/inventory/files")
    public ResponseEntity<KnowledgeFilesView> files(@RequestParam(required = false) final String sourceId,
                                                    @RequestParam(required = false) final String pathContains,
                                                    @RequestParam(required = false) final String extension,
                                                    @RequestParam(required = false) final Integer limit,
                                                    @RequestParam(required = false) final Integer offset) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.files(new KnowledgeFilesRequest(sourceId, pathContains, extension, limit, offset)));
    }

    @PostMapping("/api/v1/infrastructure/knowledge/analysis/build")
    public ResponseEntity<KnowledgeAnalysisBuildView> buildAnalysis(@RequestBody(required = false) final KnowledgeAnalysisBuildRequest request) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.buildAnalysis(request));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/jobs/{jobId}")
    public ResponseEntity<KnowledgeAnalysisJobView> analysisJob(@PathVariable final String jobId) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisJob(jobId));
    }

    @PostMapping("/api/v1/infrastructure/knowledge/analysis/jobs/{jobId}/stop")
    public ResponseEntity<KnowledgeAnalysisStopView> stopAnalysis(@PathVariable final String jobId) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.stopAnalysis(jobId));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/status")
    public ResponseEntity<KnowledgeAnalysisStatusView> analysisStatus() {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisStatus());
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/files")
    public ResponseEntity<KnowledgeAnalysisFilesView> analysisFiles(@RequestParam(required = false) final String sourceId,
                                                                    @RequestParam(required = false) final String status,
                                                                    @RequestParam(required = false) final String pathContains,
                                                                    @RequestParam(required = false) final Integer limit,
                                                                    @RequestParam(required = false) final Integer offset) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisFiles(new KnowledgeAnalysisFilesRequest(sourceId, status, pathContains, limit, offset)));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/symbols")
    public ResponseEntity<KnowledgeAnalysisSymbolsView> analysisSymbols(@RequestParam(required = false) final String sourceId,
                                                                        @RequestParam(required = false) final String role,
                                                                        @RequestParam(required = false) final String kind,
                                                                        @RequestParam(required = false) final String pathContains,
                                                                        @RequestParam(required = false) final String nameContains,
                                                                        @RequestParam(required = false) final String flowDomain,
                                                                        @RequestParam(required = false) final String factOrigin,
                                                                        @RequestParam(required = false) final Integer limit,
                                                                        @RequestParam(required = false) final Integer offset) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisSymbols(new KnowledgeAnalysisSymbolsRequest(sourceId, role, kind, pathContains, nameContains, flowDomain, factOrigin, limit, offset)));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/relations")
    public ResponseEntity<KnowledgeAnalysisRelationsView> analysisRelations(@RequestParam(required = false) final String sourceId,
                                                                            @RequestParam(required = false) final String relation,
                                                                            @RequestParam(required = false) final String fromSymbolId,
                                                                            @RequestParam(required = false) final String toSymbolId,
                                                                            @RequestParam(required = false) final String flowDomain,
                                                                            @RequestParam(required = false) final String factOrigin,
                                                                            @RequestParam(required = false) final Integer limit,
                                                                            @RequestParam(required = false) final Integer offset) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisRelations(new KnowledgeAnalysisRelationsRequest(sourceId, relation, fromSymbolId, toSymbolId, flowDomain, factOrigin, limit, offset)));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph")
    public ResponseEntity<KnowledgeAnalysisGraphView> analysisGraph(@RequestParam(required = false) final String sourceId,
                                                                    @RequestParam(required = false) final String graphNodeId,
                                                                    @RequestParam(required = false) final String graphEdgeId,
                                                                    @RequestParam(required = false) final String inventoryFileId,
                                                                    @RequestParam(required = false) final String flowDomain,
                                                                    @RequestParam(required = false) final String factOrigin,
                                                                    @RequestParam(required = false) final String nodeKind,
                                                                    @RequestParam(required = false) final String edgeType,
                                                                    @RequestParam(required = false) final Integer depth,
                                                                    @RequestParam(required = false) final Integer limit,
                                                                    @RequestParam(required = false) final Boolean includeEvidence,
                                                                    @RequestParam(required = false) final Boolean includeClaims,
                                                                    @RequestParam(required = false) final Boolean includeDiagnostics) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisGraph(new KnowledgeAnalysisGraphRequest(
                sourceId,
                graphNodeId,
                graphEdgeId,
                inventoryFileId,
                flowDomain,
                factOrigin,
                nodeKind,
                edgeType,
                depth,
                limit,
                includeEvidence,
                includeClaims,
                includeDiagnostics
        )));
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/slice")
    public ResponseEntity<KnowledgeAnalysisGraphView> analysisGraphSlice(@RequestParam(required = false) final String sourceId,
                                                                         @RequestParam(required = false) final String rootGraphNodeId,
                                                                         @RequestParam(required = false) final String stableKey,
                                                                         @RequestParam(required = false) final String flowDomain,
                                                                         @RequestParam(required = false) final String direction,
                                                                         @RequestParam(required = false) final Integer depth,
                                                                         @RequestParam(required = false) final Integer maxNodes,
                                                                         @RequestParam(required = false) final Integer maxEdges,
                                                                         @RequestParam(required = false) final String includeExternal,
                                                                         @RequestParam(required = false) final Boolean includeUnresolved,
                                                                         @RequestParam(required = false) final Boolean includeTests,
                                                                         @RequestParam(required = false) final Boolean includeWorkflow,
                                                                         @RequestParam(required = false) final String edgeTypes,
                                                                         @RequestParam(required = false) final String nodeKinds,
                                                                         @RequestParam(required = false) final Boolean includeEvidence,
                                                                         @RequestParam(required = false) final Boolean includeClaims,
                                                                         @RequestParam(required = false) final Boolean includeIsolated) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.analysisGraphSlice(new KnowledgeAnalysisGraphSliceRequest(
                sourceId,
                rootGraphNodeId,
                stableKey,
                flowDomain,
                direction,
                depth,
                maxNodes,
                maxEdges,
                includeExternal,
                includeUnresolved,
                includeTests,
                includeWorkflow,
                edgeTypes,
                nodeKinds,
                includeEvidence,
                includeClaims,
                includeIsolated
        )));
    }

    @ExceptionHandler(KnowledgeGatewayException.class)
    public ResponseEntity<KnowledgeErrorResponse> handleKnowledgeGatewayException(final KnowledgeGatewayException exception) {
        return ResponseEntity
                .status(this.httpStatus(exception.getCode()))
                .body(new KnowledgeErrorResponse(exception.getResponseCode(), exception.getMessage()));
    }

    private HttpStatus httpStatus(final KnowledgeGatewayErrorCode code) {
        return switch (code) {
            case KNOWLEDGE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case KNOWLEDGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case KNOWLEDGE_REQUEST_FAILED -> HttpStatus.BAD_REQUEST;
            case KNOWLEDGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case KNOWLEDGE_CONFLICT -> HttpStatus.CONFLICT;
            case KNOWLEDGE_BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record KnowledgeErrorResponse(String code, String message) {
    }
}
