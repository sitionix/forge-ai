package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
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

    public void complete(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        this.createImplementationTicket(ticketId, laneId, request);
        this.createApiTicket(ticketId, laneId, request);
        this.createEventTicket(ticketId, laneId, request);
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
        if (Boolean.FALSE.equals(request.getApiRequest().getRequired())) {
            this.createAgentTask.markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, Agent.API);
            return;
        }
        final AgentTicket<ApiPayload> apiTicket = this.agentTicketApiMapper.asApiTicket(request, ticketId);
        apiTicket.setScope(ScopeMode.GLOBAL_SCOPE);
        this.createAgentTask.create(apiTicket, laneId);
    }

    private void createEventTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        if (Boolean.FALSE.equals(request.getEventRequest().getRequired())) {
            this.createAgentTask.markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, Agent.EVENT);
            return;
        }
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
