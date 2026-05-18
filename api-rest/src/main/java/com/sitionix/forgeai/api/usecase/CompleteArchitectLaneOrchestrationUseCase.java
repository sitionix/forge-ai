package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.domain.model.CompleteArchitectLaneCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.CompleteArchitectLane;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompleteArchitectLaneOrchestrationUseCase {

    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final CompleteArchitectLane completeArchitectLane;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteArchitectLaneRequest request) {
        final Agent implementationAgent = this.resolveImplementationAgent(request.getImplementationHandoff().getScope());
        final CompleteArchitectLaneCommand command = CompleteArchitectLaneCommand.builder()
                .sourceLaneId(laneId)
                .implementBeTicket(Agent.IMPLEMENT_BE.equals(implementationAgent)
                        ? this.agentTicketApiMapper.asImplementBeTicket(request, ticketId)
                        : null)
                .implementFeTicket(Agent.IMPLEMENT_FE.equals(implementationAgent)
                        ? this.agentTicketApiMapper.asImplementFeTicket(request, ticketId)
                        : null)
                .apiTicket(this.agentTicketApiMapper.asApiTicket(request, ticketId))
                .eventTicket(this.agentTicketApiMapper.asEventTicket(request, ticketId))
                .apiRequired(request.getApiRequest().getRequired())
                .apiScope(request.getApiRequest().getScope())
                .eventRequired(request.getEventRequest().getRequired())
                .eventScope(request.getEventRequest().getScope())
                .build();
        this.completeArchitectLane.complete(command);
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
