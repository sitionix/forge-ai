package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log
public class CompleteAgentTasksUseCase implements CompleteAgentTasks {

    private final CreateAgentTask createAgentTask;

    public void complete(final UUID sourceLaneId, final Collection<? extends AgentTicket<? extends AgentTicketPayload>> agentTickets) {
        Objects.requireNonNull(sourceLaneId, "sourceLaneId");
        Objects.requireNonNull(agentTickets, "agentTickets");

        for (final AgentTicket<? extends AgentTicketPayload> agentTicket : agentTickets) {
            this.createAgentTask.create(agentTicket, sourceLaneId);
        }
    }
}
