package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.sitionix.forgeai.api.LaneCompletionValidator;
import com.sitionix.forgeai.api.RequestValidationException;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteQaLeadLaneOrchestrationUseCase {

    private final LaneCompletionValidator laneCompletionValidator;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final ServicePropertiesProvider servicePropertiesProvider;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.laneCompletionValidator.validateQaLeadCompletion(ticketId, laneId, request.getScope());
        final ServiceGroup serviceGroup = this.resolveServiceGroup(request.getScope());
        this.validateRequest(request, serviceGroup);
        if (ServiceGroup.BACKEND.equals(serviceGroup)) {
            this.routeBackendTestLanes(ticketId, laneId, request);
            return;
        }
        if (ServiceGroup.FRONTEND.equals(serviceGroup)) {
            this.routeFrontendUiLane(laneId, request);
            return;
        }
        throw new RequestValidationException("Unsupported service group for QA lead scope=" + request.getScope());
    }

    private void routeBackendTestLanes(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        this.routeBackendUnitLane(ticketId, laneId, request);
        this.routeBackendIntegrationLane(ticketId, laneId, request);
    }

    private void routeBackendUnitLane(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired())) {
            final AgentTicket<TestUnitPayload> testUnitTicket = this.agentTicketApiMapper.asTestUnitTicket(request, ticketId);
            this.createAgentTask.create(testUnitTicket, laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UNIT);
    }

    private void routeBackendIntegrationLane(final UUID ticketId, final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired())) {
            final AgentTicket<TestItPayload> testItTicket = this.agentTicketApiMapper.asTestItTicket(request, ticketId);
            this.createAgentTask.create(testItTicket, laneId);
            return;
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_IT);
    }

    private void routeFrontendUiLane(final UUID laneId, final CompleteQaLeadLaneRequestDTO request) {
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired())
                || Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired())) {
            throw new RequestValidationException("Unit and integration test lanes are not supported for frontend scopes");
        }
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired())) {
            throw new RequestValidationException("uiTestRequired must be false for frontend scopes");
        }
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UI);
    }

    private ServiceGroup resolveServiceGroup(final String scope) {
        return this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> scope.equals(value.getPath()))
                .map(ServicePropertiesProvider.ServiceConfigView::getGroup)
                .findFirst()
                .orElseThrow(() -> new RequestValidationException("Unknown service scope: " + scope));
    }

    private void validateRequest(final CompleteQaLeadLaneRequestDTO request, final ServiceGroup serviceGroup) {
        if (request.getTestLaneRequirements() == null) {
            throw new RequestValidationException("testLaneRequirements must be provided");
        }
        if (request.getTestLaneRequirements().getUnitTestRequired() == null
                || request.getTestLaneRequirements().getIntegrationTestRequired() == null
                || request.getTestLaneRequirements().getUiTestRequired() == null) {
            throw new RequestValidationException("All test lane routing flags must be provided");
        }
        if (request.getScope() == null || request.getScope().isBlank()) {
            throw new RequestValidationException("scope must not be blank");
        }
        if (request.getSummary() == null || request.getSummary().isBlank()) {
            throw new RequestValidationException("summary must not be blank");
        }
        if (Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired())
                && (request.getIntegrationTestCases() == null || request.getIntegrationTestCases().isEmpty())) {
            throw new RequestValidationException("integrationTestCases must not be empty when integrationTestRequired is true");
        }
        if (ServiceGroup.BACKEND.equals(serviceGroup)
                && Boolean.TRUE.equals(request.getTestLaneRequirements().getUiTestRequired())) {
            throw new RequestValidationException("uiTestRequired must be false for backend scopes");
        }
        if (ServiceGroup.FRONTEND.equals(serviceGroup)
                && (Boolean.TRUE.equals(request.getTestLaneRequirements().getUnitTestRequired())
                || Boolean.TRUE.equals(request.getTestLaneRequirements().getIntegrationTestRequired()))) {
            throw new RequestValidationException("Unit and integration test lanes are not supported for frontend scopes");
        }
    }
}
