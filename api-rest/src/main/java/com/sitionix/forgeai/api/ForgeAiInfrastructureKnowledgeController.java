package com.sitionix.forgeai.api;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/api/v1/infrastructure/knowledge/search")
    public ResponseEntity<KnowledgeSearchResultView> search(@RequestBody final KnowledgeSearchRequest request) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.search(request));
    }

    @PostMapping("/api/v1/infrastructure/knowledge/context")
    public ResponseEntity<KnowledgeContextView> context(@RequestBody final KnowledgeContextRequest request) {
        return ResponseEntity.ok(this.manageKnowledgeInfrastructure.context(request));
    }

    @ExceptionHandler(KnowledgeGatewayException.class)
    public ResponseEntity<KnowledgeErrorResponse> handleKnowledgeGatewayException(final KnowledgeGatewayException exception) {
        return ResponseEntity
                .status(this.httpStatus(exception.getCode()))
                .body(new KnowledgeErrorResponse(exception.getCode().name(), exception.getMessage()));
    }

    private HttpStatus httpStatus(final KnowledgeGatewayErrorCode code) {
        return switch (code) {
            case SEARCH_QUERY_INVALID, CONTEXT_QUERY_INVALID -> HttpStatus.BAD_REQUEST;
            case KNOWLEDGE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case KNOWLEDGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case KNOWLEDGE_BAD_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record KnowledgeErrorResponse(String code, String message) {
    }
}
