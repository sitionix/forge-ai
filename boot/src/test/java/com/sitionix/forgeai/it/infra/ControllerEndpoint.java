package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
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

    public static Endpoint<CompleteAnalyzerLaneRequestDTO, CompleteAnalyzerLaneResponseDTO> completeAnalyzerLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete",
                HttpMethod.POST,
                CompleteAnalyzerLaneRequestDTO.class,
                CompleteAnalyzerLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteAnalyzerLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteAnalyzerLaneRequestDTO, CompleteAnalyzerLaneResponseDTO> completeAnalyzerLaneScopeMismatch() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete",
                HttpMethod.POST,
                CompleteAnalyzerLaneRequestDTO.class,
                CompleteAnalyzerLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteAnalyzerLane.json")
                        .expectStatus(HttpStatus.BAD_REQUEST.value())
        );
    }

    public static Endpoint<CompleteArchitectLaneRequest, CompleteArchitectLaneResponse> completeArchitectLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/architect/complete",
                HttpMethod.POST,
                CompleteArchitectLaneRequest.class,
                CompleteArchitectLaneResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteArchitectLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteArchitectLaneRequest, CompleteArchitectLaneResponse> completeArchitectLaneScopeMismatch() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/architect/complete",
                HttpMethod.POST,
                CompleteArchitectLaneRequest.class,
                CompleteArchitectLaneResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteArchitectLaneBffApiEventRequired.json")
                        .expectStatus(HttpStatus.BAD_REQUEST.value())
        );
    }

    public static Endpoint<CompleteApiLaneRequest, CompleteApiLaneResponse> completeApiLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/api/complete",
                HttpMethod.POST,
                CompleteApiLaneRequest.class,
                CompleteApiLaneResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteApiLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteApiLaneRequest, CompleteApiLaneResponse> completeApiLaneScopeMismatch() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/api/complete",
                HttpMethod.POST,
                CompleteApiLaneRequest.class,
                CompleteApiLaneResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteApiLaneScopeMismatch.json")
                        .expectStatus(HttpStatus.BAD_REQUEST.value())
        );
    }

    public static Endpoint<CompleteImplementBeLaneRequestDTO, CompleteImplementBeLaneResponseDTO> completeImplementBeLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/implement-be/complete",
                HttpMethod.POST,
                CompleteImplementBeLaneRequestDTO.class,
                CompleteImplementBeLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteImplementBeLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteImplementFeLaneRequestDTO, CompleteImplementFeLaneResponseDTO> completeImplementFeLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/implement-fe/complete",
                HttpMethod.POST,
                CompleteImplementFeLaneRequestDTO.class,
                CompleteImplementFeLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteImplementFeLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteQaLeadLaneRequestDTO, CompleteQaLeadLaneResponseDTO> completeQaLeadLaneBackend() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/qa-lead/complete",
                HttpMethod.POST,
                CompleteQaLeadLaneRequestDTO.class,
                CompleteQaLeadLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteQaLeadLaneBackend.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteQaLeadLaneRequestDTO, CompleteQaLeadLaneResponseDTO> completeQaLeadLaneBackendNotRequired() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/qa-lead/complete",
                HttpMethod.POST,
                CompleteQaLeadLaneRequestDTO.class,
                CompleteQaLeadLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteQaLeadLaneBackendNotRequired.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteItTestLaneRequestDTO, CompleteItTestLaneResponseDTO> completeItTestLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/test-it/complete",
                HttpMethod.POST,
                CompleteItTestLaneRequestDTO.class,
                CompleteItTestLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteItTestLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteUiTestLaneRequestDTO, CompleteUiTestLaneResponseDTO> completeUiTestLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/test-ui/complete",
                HttpMethod.POST,
                CompleteUiTestLaneRequestDTO.class,
                CompleteUiTestLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteUiTestLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<CompleteUnitTestLaneRequestDTO, CompleteUnitTestLaneResponseDTO> completeUnitTestLane() {
        return Endpoint.createContract(
                "/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/test-unit/complete",
                HttpMethod.POST,
                CompleteUnitTestLaneRequestDTO.class,
                CompleteUnitTestLaneResponseDTO.class,
                (MockmvcDefault) context -> context
                        .withRequest("requestCompleteUnitTestLane.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    private ControllerEndpoint() {
    }
}
