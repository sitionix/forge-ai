package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("reviewerAgentExecutor")
public class ReviewerAgentExecutor extends TaskDrivenCodexAgentExecutor implements ExecuteAgent<ReviewerPayload> {

    public ReviewerAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                                 final CodexClient codexClient,
                                 final AgentTicketRepository agentTicketRepository,
                                 final TicketRepository ticketRepository) {
        super(prepareAgentExecutionInputUseCase, codexClient, agentTicketRepository, ticketRepository);
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute reviewer lane: " + lane.getLaneId());
        this.executeWithTasks(lane);
    }
}
