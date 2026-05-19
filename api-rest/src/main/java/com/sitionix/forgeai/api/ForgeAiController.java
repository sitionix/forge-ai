package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiApi;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ForgeAiController implements ForgeAiApi {

    private final StartForgeAiTask startForgeAiTask;
    private final ForgeAiApiMapper forgeAiApiMapper;
    private final TerminalTtyResolver terminalTtyResolver;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final CreateAgentTask createAgentTask;
    private final CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;
    private final CompleteApiLaneOrchestrationUseCase completeApiLaneOrchestrationUseCase;

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

        final AgentTicket<ArchitectPayload> architectTicket = this.agentTicketApiMapper.asArchitectTicket(completeAnalyzerLaneRequestDTO, ticketId);
        final AgentTicket<QaLeadPayload> qaLeadTicket = this.agentTicketApiMapper.asQaLeadTicket(completeAnalyzerLaneRequestDTO, ticketId);

        this.createAgentTask.create(architectTicket, laneId);
        this.createAgentTask.create(qaLeadTicket, laneId);

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
        this.completeApiLaneOrchestrationUseCase.complete(ticketId, laneId, completeApiLaneRequest);

        return ResponseEntity.ok(CompleteApiLaneResponse.builder()
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }
}
