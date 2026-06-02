package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("testUiAgentExecutor")
public class TestUiAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<TestUiPayload> {

    public TestUiAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                               final AgentTicketRepository agentTicketRepository,
                               final TicketRepository ticketRepository,
                               final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                               final SupervisedExecutionProperties supervisedExecutionProperties) {
        super(prepareAgentExecutionInputUseCase, agentTicketRepository, ticketRepository, supervisedLaneExecutionUseCase, supervisedExecutionProperties);
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute test_ui lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }
}
