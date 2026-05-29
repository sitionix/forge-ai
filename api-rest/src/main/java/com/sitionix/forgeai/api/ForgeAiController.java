package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiApi;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
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
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteReviewerLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteItTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteQaLeadLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteUnitTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ApiLaneEvidencePayloadApiMapper;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ForgeAiController implements ForgeAiApi {

    private final StartForgeAiTask startForgeAiTask;
    private final ForgeAiApiMapper forgeAiApiMapper;
    private final TerminalTtyResolver terminalTtyResolver;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ApiLaneEvidencePayloadApiMapper apiLaneEvidencePayloadApiMapper;
    private final CompleteAgentTasks completeAgentTasks;
    private final LaneScopeValidator laneScopeValidator;
    private final CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;
    private final CompleteApiLaneOrchestrationUseCase completeApiLaneOrchestrationUseCase;
    private final ValidateApiLaneEvidence validateApiLaneEvidence;
    private final CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;
    private final CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;
    private final CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;
    private final CompleteReviewerTask completeReviewerTaskUseCase;

    @Override
    public ResponseEntity<StartForgeResponseDTO> startForge(@Valid final StartForgeRequestDTO startForgeRequestDTO) {

        final ForgeAiStartCommand command = this.forgeAiApiMapper
                .asForgeAiStartCommand(startForgeRequestDTO, this.terminalTtyResolver.resolve());
        final Ticket startedTask = this.startForgeAiTask.execute(command);
        final StartForgeResponseDTO response = this.forgeAiApiMapper.asStartForgeResponseDto(startedTask);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    public ResponseEntity<CompleteAnalyzerLaneResponseDTO> completeAnalyzerLane(final UUID ticketId, final UUID laneId, @Valid final CompleteAnalyzerLaneRequestDTO completeAnalyzerLaneRequestDTO) {
        log.info("Received completeAnalyzerLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, completeAnalyzerLaneRequestDTO);
        this.laneScopeValidator.validateAnalyzerCallbackScope(
                laneId,
                completeAnalyzerLaneRequestDTO.getArchitectHandoff() == null ? null : completeAnalyzerLaneRequestDTO.getArchitectHandoff().getScope(),
                completeAnalyzerLaneRequestDTO.getQaLeadHandoff() == null ? null : completeAnalyzerLaneRequestDTO.getQaLeadHandoff().getScope()
        );

        final AgentTicket<ArchitectPayload> architectTicket = this.agentTicketApiMapper.asArchitectTicket(completeAnalyzerLaneRequestDTO, ticketId);
        final AgentTicket<QaLeadPayload> qaLeadTicket = this.agentTicketApiMapper.asQaLeadTicket(completeAnalyzerLaneRequestDTO, ticketId);

        this.completeAgentTasks.complete(laneId, List.of(architectTicket, qaLeadTicket));

        return ResponseEntity.ok(CompleteAnalyzerLaneResponseDTO.builder()
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteArchitectLaneResponse> completeArchitectLane(final UUID ticketId, final UUID laneId, @Valid final CompleteArchitectLaneRequest completeArchitectLaneRequest) {
        log.info("Received completeArchitectLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, completeArchitectLaneRequest);
        this.completeArchitectLaneOrchestrationUseCase.complete(ticketId, laneId, completeArchitectLaneRequest);

        return ResponseEntity.ok(CompleteArchitectLaneResponse.builder()
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteApiLaneResponse> completeApiLane(final UUID ticketId, final UUID laneId, @Valid final CompleteApiLaneRequest completeApiLaneRequest) {
        log.info("Received completeApiLane request for ticketId: {}, laneId: {}, with request body: {}", ticketId, laneId, completeApiLaneRequest);
        final boolean apiLaneRequiresCompletion = this.laneScopeValidator.validateApiCompletion(laneId);
        if (apiLaneRequiresCompletion) {
            final Set<String> callbackScopes = completeApiLaneRequest.getContracts() == null
                    ? Set.of()
                    : completeApiLaneRequest.getContracts().stream()
                    .filter(Objects::nonNull)
                    .map(ApiLaneContractResult::getScope)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            this.validateApiLaneEvidence.validate(laneId, callbackScopes, this.apiLaneEvidencePayloadApiMapper.asApiLaneEvidencePayloadOrEmpty(completeApiLaneRequest));
            this.completeApiLaneOrchestrationUseCase.complete(ticketId, laneId, completeApiLaneRequest);
        }

        return ResponseEntity.ok(CompleteApiLaneResponse.builder()
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteImplementBeLaneResponseDTO> completeImplementBeLane(final UUID ticketId,
                                                                                       final UUID laneId,
                                                                                       @Valid final CompleteImplementBeLaneRequestDTO completeImplementBeLaneRequestDTO) {
        log.info("Received completeImplementBeLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, completeImplementBeLaneRequestDTO);
        this.laneScopeValidator.validateImplementBeCallbackScope(laneId, completeImplementBeLaneRequestDTO.getScope());
        final AgentTicket<TestUnitPayload> testUnitTicket = this.agentTicketApiMapper.asTestUnitTicket(completeImplementBeLaneRequestDTO, ticketId);
        final AgentTicket<TestItPayload> testItTicket = this.agentTicketApiMapper.asTestItTicket(completeImplementBeLaneRequestDTO, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(testUnitTicket, testItTicket));

        return ResponseEntity.ok(CompleteImplementBeLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteImplementFeLaneResponseDTO> completeImplementFeLane(final UUID ticketId,
                                                                                       final UUID laneId,
                                                                                       @Valid final CompleteImplementFeLaneRequestDTO completeImplementFeLaneRequestDTO) {
        log.info("Received completeImplementFeLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, completeImplementFeLaneRequestDTO);
        this.laneScopeValidator.validateImplementFeCallbackScope(laneId, completeImplementFeLaneRequestDTO.getScope());
        final AgentTicket<TestUiPayload> testUiTicket = this.agentTicketApiMapper.asTestUiTicket(completeImplementFeLaneRequestDTO, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(testUiTicket));

        return ResponseEntity.ok(CompleteImplementFeLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteQaLeadLaneResponseDTO> completeQaLeadLane(final UUID ticketId,
                                                                            final UUID laneId,
                                                                            @Valid final CompleteQaLeadLaneRequestDTO completeQaLeadLaneRequestDTO) {
        this.completeQaLeadLaneOrchestrationUseCase.complete(ticketId, laneId, completeQaLeadLaneRequestDTO);
        return ResponseEntity.ok(CompleteQaLeadLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteItTestLaneResponseDTO> completeItTestLane(final UUID ticketId,
                                                                            final UUID laneId,
                                                                            @Valid final CompleteItTestLaneRequestDTO completeItTestLaneRequestDTO) {
        this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, completeItTestLaneRequestDTO);
        return ResponseEntity.ok(CompleteItTestLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteUiTestLaneResponseDTO> completeUiTestLane(final UUID ticketId,
                                                                             final UUID laneId,
                                                                             @Valid final CompleteUiTestLaneRequestDTO completeUiTestLaneRequestDTO) {
        log.info("Received completeUiTestLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, completeUiTestLaneRequestDTO);
        this.laneScopeValidator.validateTestUiCallbackScope(laneId, completeUiTestLaneRequestDTO.getScope());
        this.completeAgentTasks.complete(laneId, List.of());
        return ResponseEntity.ok(CompleteUiTestLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteUnitTestLaneResponseDTO> completeUnitTestLane(final UUID ticketId,
                                                                                final UUID laneId,
                                                                                @Valid final CompleteUnitTestLaneRequestDTO completeUnitTestLaneRequestDTO) {
        log.info("Received completeUnitTestLane request for ticketId: {}, laneId: {}, with request body: {}",
                ticketId, laneId, completeUnitTestLaneRequestDTO);
        this.completeUnitTestLaneOrchestrationUseCase.complete(ticketId, laneId, completeUnitTestLaneRequestDTO);

        return ResponseEntity.ok(CompleteUnitTestLaneResponseDTO.builder()
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    @Override
    public ResponseEntity<CompleteReviewerLaneResponseDTO> completeReviewerLane(final UUID ticketId) {
        this.completeReviewerTaskUseCase.complete(ticketId);
        return ResponseEntity.ok(CompleteReviewerLaneResponseDTO.builder()
                .status(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }
}
