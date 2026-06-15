package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatResponse;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
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

    public static Endpoint<JarvisChatRequest, JarvisChatResponse> jarvisChat() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/jarvis/chat",
                HttpMethod.POST,
                JarvisChatRequest.class,
                JarvisChatResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestJarvisChat.json")
                        .expectResponse("responseJarvisChat.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<Void, JarvisStatusView> jarvisStatus() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/jarvis/status",
                HttpMethod.GET,
                Void.class,
                JarvisStatusView.class,
                (MockmvcDefault) context -> context
                        .expectResponse("responseJarvisStatus.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<Void, JarvisActionsView> jarvisActions() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/jarvis/actions",
                HttpMethod.GET,
                Void.class,
                JarvisActionsView.class,
                (MockmvcDefault) context -> context
                        .expectResponse("responseJarvisActions.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<JarvisCommandRequest, JarvisCommandResultView> jarvisCommand() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/jarvis/command",
                HttpMethod.POST,
                JarvisCommandRequest.class,
                JarvisCommandResultView.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestJarvisCommand.json")
                        .expectResponse("responseJarvisCommand.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<Void, KnowledgeAnalysisStopView> knowledgeAnalysisStop() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/knowledge/analysis/jobs/{jobId}/stop",
                HttpMethod.POST,
                Void.class,
                KnowledgeAnalysisStopView.class,
                (MockmvcDefault) context -> context
                        .expectResponse("responseKnowledgeAnalysisStop.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<Void, KnowledgeInventoryStatusView> knowledgeInventoryStatus() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/knowledge/inventory/status",
                HttpMethod.GET,
                Void.class,
                KnowledgeInventoryStatusView.class,
                (MockmvcDefault) context -> context.expectStatus(HttpStatus.OK.value())
        );
    }

    private ControllerEndpoint() {
    }
}
