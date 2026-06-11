package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public class ControllerEndpoint {

    public static Endpoint<StartForgeRequestDTO, StartForgeResponseDTO> startForge() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/start",
                HttpMethod.POST,
                StartForgeRequestDTO.class,
                StartForgeResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestStartForge.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .header("X-Terminal-TTY", "/dev/ttys999")
        );
    }

    public static Endpoint<StartForgeRequestDTO, StartForgeResponseDTO> startForgeFrontend() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/start",
                HttpMethod.POST,
                StartForgeRequestDTO.class,
                StartForgeResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestStartForgeFrontend.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .header("X-Terminal-TTY", "/dev/ttys999")
        );
    }

    public static Endpoint<KnowledgeContextRequest, KnowledgeContextView> knowledgeContext() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/knowledge/context",
                HttpMethod.POST,
                KnowledgeContextRequest.class,
                KnowledgeContextView.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestKnowledgeContext.json")
                        .expectResponse("responseKnowledgeContext.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<KnowledgeInventoryBuildRequest, KnowledgeInventoryBuildResultView> knowledgeInventoryBuild() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/knowledge/inventory/build",
                HttpMethod.POST,
                KnowledgeInventoryBuildRequest.class,
                KnowledgeInventoryBuildResultView.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestKnowledgeInventoryBuild.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    private ControllerEndpoint() {
    }
}
