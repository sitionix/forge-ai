package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("qaLeadAgentExecutor")
public class QaLeadAgentExecutor extends TaskDrivenCodexAgentExecutor implements ExecuteAgent<QaLeadPayload> {

    public QaLeadAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                               final CodexClient codexClient,
                               final AgentTicketRepository agentTicketRepository,
                               final TicketRepository ticketRepository) {
        super(prepareAgentExecutionInputUseCase, codexClient, agentTicketRepository, ticketRepository);
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute qa_lead lane: " + lane.getLaneId());
        this.executeWithTasks(lane, QaLeadPayload.class);
    }
}
