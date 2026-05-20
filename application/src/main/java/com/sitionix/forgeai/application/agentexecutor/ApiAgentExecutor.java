package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("apiAgentExecutor")
public class ApiAgentExecutor extends TaskDrivenCodexAgentExecutor implements ExecuteAgent<ApiPayload> {

    public ApiAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                            final CodexClient codexClient,
                            final AgentTicketRepository agentTicketRepository,
                            final TicketRepository ticketRepository) {
        super(prepareAgentExecutionInputUseCase, codexClient, agentTicketRepository, ticketRepository);
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute api lane: " + lane.getLaneId());
        this.executeWithTasks(lane, ApiPayload.class);
    }
}
