package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompleteImplementBeLaneOrchestrationUseCase {

    private final LaneScopeValidator laneScopeValidator;
    private final CreateAgentTask createAgentTask;
    private final AgentTicketApiMapper agentTicketApiMapper;

    public void complete(final UUID ticketId, final UUID laneId, final CompleteImplementBeLaneRequestDTO request) {
        this.laneScopeValidator.validateImplementBeCallbackScope(laneId, request.getScope());
        this.createAgentTask.create(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId), laneId);
        this.createAgentTask.create(this.agentTicketApiMapper.asTestItTicket(request, ticketId), laneId);
    }
}
