package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiApi;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
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

import java.util.Objects;
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
    private final ServicePropertiesProvider servicePropertiesProvider;

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
        final Agent implementationAgent = this.resolveImplementationAgent(completeArchitectLaneRequest.getImplementationHandoff().getScope());
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            final AgentTicket<ImplementBePayload> implementBeTicket = this.agentTicketApiMapper.asImplementBeTicket(completeArchitectLaneRequest, ticketId);
            this.createAgentTask.create(implementBeTicket, laneId);
        } else if (Agent.IMPLEMENT_FE.equals(implementationAgent)) {
            final AgentTicket<ImplementFePayload> implementFeTicket = this.agentTicketApiMapper.asImplementFeTicket(completeArchitectLaneRequest, ticketId);
            this.createAgentTask.create(implementFeTicket, laneId);
        }

        if (Boolean.FALSE.equals(completeArchitectLaneRequest.getApiRequest().getRequired())) {
            this.createAgentTask.markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, Agent.API);
        } else {
            final AgentTicket<ApiPayload> apiTicket = this.agentTicketApiMapper.asApiTicket(completeArchitectLaneRequest, ticketId);
            apiTicket.setScope(ScopeMode.GLOBAL_SCOPE);
            this.createAgentTask.create(apiTicket, laneId);
        }

        if (Boolean.FALSE.equals(completeArchitectLaneRequest.getEventRequest().getRequired())) {
            this.createAgentTask.markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, Agent.EVENT);
        } else {
            final AgentTicket<EventPayload> eventTicket = this.agentTicketApiMapper.asEventTicket(completeArchitectLaneRequest, ticketId);
            eventTicket.setScope(ScopeMode.GLOBAL_SCOPE);
            this.createAgentTask.create(eventTicket, laneId);
        }

        return ResponseEntity.ok(CompleteArchitectLaneResponse.builder()
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .ticketId(ticketId)
                .build());
    }

    private Agent resolveImplementationAgent(final String scope) {
        final ServiceGroup group = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.equals(value.getPath(), scope))
                .map(ServicePropertiesProvider.ServiceConfigView::getGroup)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Service scope not found: " + scope));
        if (ServiceGroup.BACKEND.equals(group)) {
            return Agent.IMPLEMENT_BE;
        }
        if (ServiceGroup.FRONTEND.equals(group)) {
            return Agent.IMPLEMENT_FE;
        }
        throw new IllegalArgumentException("Unsupported service group for implementation lane: " + group);
    }
}
