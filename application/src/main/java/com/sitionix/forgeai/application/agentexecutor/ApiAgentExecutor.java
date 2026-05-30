package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
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

    private final SupervisedExecutionProperties supervisedExecutionProperties;
    private final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    public ApiAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                            final CodexClient codexClient,
                            final AgentTicketRepository agentTicketRepository,
                            final TicketRepository ticketRepository,
                            final SupervisedExecutionProperties supervisedExecutionProperties,
                            final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase) {
        super(prepareAgentExecutionInputUseCase, codexClient, agentTicketRepository, ticketRepository);
        this.supervisedExecutionProperties = supervisedExecutionProperties;
        this.supervisedLaneExecutionUseCase = supervisedLaneExecutionUseCase;
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute api lane: " + lane.getLaneId());
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareInputWithOptionalTasks(lane);
        if (this.supervisedExecutionProperties.isSupervisedAgent(lane.getAgent().getId())) {
            this.supervisedLaneExecutionUseCase.execute(lane, enrichedInput, this.supervisedExecutionProperties.getCorrectionAttempts());
            return;
        }
        this.codexClient.submit(enrichedInput, lane.getSourceTerminalTty());
    }
}
