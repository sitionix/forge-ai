package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;

@Component
@RequiredArgsConstructor
public class CompleteQaLeadLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ServicePropertiesProvider servicePropertiesProvider;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.laneScopeValidator.validateQaLeadCompletion(ticketId, laneId, request.getScope());
        final ServiceGroup group = this.resolveScopeGroup(request.getScope());
        this.handleDeferredUiTesting(laneId, request, group);
        if (ServiceGroup.FRONTEND.equals(group)) {
            return;
        }
        this.routeTestLanes(ticketId, laneId, request);
    }

    private void routeTestLanes(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.routeTestLane(
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired()),
                Agent.TEST_UNIT,
                () -> this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)
        );
        this.routeTestLane(
                laneId,
                request.getScope(),
                Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired()),
                Agent.TEST_IT,
                () -> this.agentTicketApiMapper.asTestItTicket(request, ticketId)
        );
    }

    private void handleDeferredUiTesting(final UUID laneId,
                                         final CompleteQaLeadLaneRequestDTO request,
                                         final ServiceGroup group) {
        if (!ServiceGroup.FRONTEND.equals(group)) {
            return;
        }
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "QA lead UI test routing is not supported yet: scope=" + request.getScope());
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UI);
    }

    private ServiceGroup resolveScopeGroup(final String scope) {
        return this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> java.util.Objects.equals(value.getPath(), scope))
                .findFirst()
                .map(ServicePropertiesProvider.ServiceConfigView::getGroup)
                .orElse(null);
    }

    private <P extends AgentTicketPayload> void routeTestLane(final UUID laneId,
                                                               final String scope,
                                                               final boolean required,
                                                               final Agent agent,
                                                               final Supplier<AgentTicket<P>> ticketSupplier) {
        if (required) {
            this.createAgentTask.create(ticketSupplier.get(), laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, scope, agent);
    }
}
