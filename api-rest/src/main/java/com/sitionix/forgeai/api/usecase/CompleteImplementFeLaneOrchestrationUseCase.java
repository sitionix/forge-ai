package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class CompleteImplementFeLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final AgentTicketApiMapper agentTicketApiMapper;
    private final AgentTicketRepository agentTicketRepository;
    private final CreateAgentTask createAgentTask;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteImplementFeLaneRequestDTO request) {
        this.laneScopeValidator.validateImplementFeCompletion(ticketId, laneId, request.getScope());
        this.validateFrontendScope(request.getScope());

        final AgentTicket<ImplementFeCompletionPayload> completionReport =
                this.agentTicketApiMapper.asImplementFeCompletionTicket(request, ticketId, laneId);
        this.agentTicketRepository.save(completionReport);
        this.createAgentTask.markAsNotNeeded(laneId, request.getScope(), Agent.TEST_UI);
    }

    private void validateFrontendScope(final String scope) {
        final ServicePropertiesProvider.ServiceConfigView service = this.servicePropertiesProvider.getServices().values().stream()
                .filter(value -> Objects.equals(value.getPath(), scope))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Implement-fe scope is not configured: scope=" + scope));
        if (!Objects.equals(service.getGroup(), ServiceGroup.FRONTEND)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Implement-fe scope must be frontend: scope=" + scope + ", group=" + service.getGroup());
        }
    }
}
