package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.sitionix.forgeai.api.LaneScopeValidator;
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
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteArchitectLaneOrchestrationUseCase {

    private final AgentTicketApiMapper agentTicketApiMapper;
    private final CompleteAgentTasks completeAgentTasks;
    private final CreateAgentTask createAgentTask;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final LaneScopeValidator laneScopeValidator;
    private final LaneRepository laneRepository;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        this.laneScopeValidator.validateArchitectCallbackScope(laneId, request.getImplementationHandoff().getScope());
        this.createImplementationTicket(ticketId, laneId, request);
        this.createApiTicket(ticketId, laneId, request);
        this.createEventTicket(ticketId, laneId, request);
    }

    private void createImplementationTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final Agent implementationAgent = this.resolveImplementationAgent(request.getImplementationHandoff().getScope());
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            final AgentTicket<ImplementBePayload> implementBeTicket = this.agentTicketApiMapper.asImplementBeTicket(request, ticketId);
            this.completeAgentTasks.complete(laneId, List.of(implementBeTicket));
            return;
        }
        final AgentTicket<ImplementFePayload> implementFeTicket = this.agentTicketApiMapper.asImplementFeTicket(request, ticketId);
        this.completeAgentTasks.complete(laneId, List.of(implementFeTicket));
    }

    private void createApiTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        this.createOrMarkNotNeeded(
                this.shouldCreateApiTask(request.getApiRequest()),
                laneId,
                ScopeMode.GLOBAL_SCOPE,
                Agent.API,
                () -> {
                    final AgentTicket<ApiPayload> apiTicket = this.agentTicketApiMapper.asApiTicket(request, ticketId);
                    apiTicket.setScope(ScopeMode.GLOBAL_SCOPE);
                    return apiTicket;
                }
        );
    }

    private void createEventTicket(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final boolean hasEventLane = this.laneRepository.findProducedLanes(laneId).stream()
                .map(Lane::getAgent)
                .anyMatch(Agent.EVENT::equals);
        if (!hasEventLane) {
            return;
        }
        this.createOrMarkNotNeeded(
                this.shouldCreateEventTask(request.getEventRequest()),
                laneId,
                ScopeMode.GLOBAL_SCOPE,
                Agent.EVENT,
                () -> {
                    final AgentTicket<EventPayload> eventTicket = this.agentTicketApiMapper.asEventTicket(request, ticketId);
                    eventTicket.setScope(ScopeMode.GLOBAL_SCOPE);
                    return eventTicket;
                }
        );
    }

    private void createOrMarkNotNeeded(final boolean required,
                                       final UUID laneId,
                                       final String scope,
                                       final Agent targetAgent,
                                       final Supplier<AgentTicket<?>> ticketSupplier) {
        if (required) {
            this.completeAgentTasks.complete(laneId, List.of(ticketSupplier.get()));
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, scope, targetAgent);
    }

    private boolean shouldCreateApiTask(final ArchitectApiRequest apiRequest) {
        if (Boolean.TRUE.equals(apiRequest.getRequired())) {
            return true;
        }
        return Objects.nonNull(apiRequest.getOperations()) && !apiRequest.getOperations().isEmpty();
    }

    private boolean shouldCreateEventTask(final ArchitectEventRequest eventRequest) {
        if (Boolean.TRUE.equals(eventRequest.getRequired())) {
            return true;
        }
        return (Objects.nonNull(eventRequest.getEventName()) && !eventRequest.getEventName().isBlank())
                || (Objects.nonNull(eventRequest.getPayloadFields()) && !eventRequest.getPayloadFields().isEmpty())
                || (Objects.nonNull(eventRequest.getConsumers()) && !eventRequest.getConsumers().isEmpty());
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
