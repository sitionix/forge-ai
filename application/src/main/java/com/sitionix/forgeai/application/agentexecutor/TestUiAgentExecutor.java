package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("testUiAgentExecutor")
public class TestUiAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<TestUiPayload> {

    public TestUiAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                               final AgentTicketRepository agentTicketRepository,
                               final TicketRepository ticketRepository,
                               final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                               final SupervisedExecutionProperties supervisedExecutionProperties,
                               final LaneCompletionSupport laneCompletionSupport,
                               final CompleteAgentTasks completeAgentTasks) {
        super(
                prepareAgentExecutionInputUseCase,
                agentTicketRepository,
                ticketRepository,
                supervisedLaneExecutionUseCase,
                supervisedExecutionProperties,
                laneCompletionSupport,
                completeAgentTasks
        );
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute test_ui lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final Object scope = completionPayload.get("scope");
        if (scope != null) {
            this.laneCompletionSupport.requireExpectedScope(lane, Objects.toString(scope, null));
        }
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.completeAgentTasks.complete(lane.getLaneId(), List.of());
    }
}
