package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("feAgentExecutor")
public class FeAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<ImplementFePayload> {

    public FeAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                           final AgentTicketRepository agentTicketRepository,
                           final TicketRepository ticketRepository,
                           final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                           final SupervisedExecutionProperties supervisedExecutionProperties) {
        super(prepareAgentExecutionInputUseCase, agentTicketRepository, ticketRepository, supervisedLaneExecutionUseCase, supervisedExecutionProperties);
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute implement_fe lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }
}
