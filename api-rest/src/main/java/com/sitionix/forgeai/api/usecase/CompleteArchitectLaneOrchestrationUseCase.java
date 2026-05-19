package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.api.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteArchitectLaneOrchestrationUseCase {

    private final AgentTicketApiMapper agentTicketApiMapper;
    private final CreateAgentTask createAgentTask;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final TicketRepository ticketRepository;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        this.validateImplementationScope(laneId, request.getImplementationHandoff().getScope());
        this.createImplementationTicket(ticketId, laneId, request);
        this.createApiTicket(ticketId, laneId, request);
        this.createEventTicket(ticketId, laneId, request);
    }

    private void validateImplementationScope(final UUID laneId, final String requestScope) {
        final Lane sourceLane = this.ticketRepository.findByLaneId(laneId)
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));
        if (Objects.equals(sourceLane.getScope(), requestScope)) {
            return;
        }
        throw new ScopeMismatchException("Implementation scope mismatch: laneId=" + laneId
                + ", laneScope=" + sourceLane.getScope()
                + ", requestScope=" + requestScope);
    }

    private void createImplementationTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final Agent implementationAgent = this.resolveImplementationAgent(request.getImplementationHandoff().getScope());
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            final AgentTicket<ImplementBePayload> implementBeTicket = this.agentTicketApiMapper.asImplementBeTicket(request, ticketId);
            this.createAgentTask.create(implementBeTicket, laneId);
            return;
        }
        final AgentTicket<ImplementFePayload> implementFeTicket = this.agentTicketApiMapper.asImplementFeTicket(request, ticketId);
        this.createAgentTask.create(implementFeTicket, laneId);
    }

    private void createApiTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final AgentTicket<ApiPayload> apiTicket = this.agentTicketApiMapper.asApiTicket(request, ticketId);
        apiTicket.setScope(ScopeMode.GLOBAL_SCOPE);
        this.createAgentTask.create(apiTicket, laneId);
    }

    private void createEventTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final AgentTicket<EventPayload> eventTicket = this.agentTicketApiMapper.asEventTicket(request, ticketId);
        eventTicket.setScope(ScopeMode.GLOBAL_SCOPE);
        this.createAgentTask.create(eventTicket, laneId);
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
