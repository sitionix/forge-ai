package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.usecase.StartAgentTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class StartAgentTaskImpl implements StartAgentTask {
    @Override
    public <P extends AgentTicketPayload> void execute(AgentTicket<P> agentTicket) {

    }
}
