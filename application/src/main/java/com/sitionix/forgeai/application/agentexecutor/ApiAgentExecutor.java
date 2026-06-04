package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.List;
import java.util.Map;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("apiAgentExecutor")
public class ApiAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<ApiPayload> {

    public ApiAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
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
        log.info("Execute api lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.requireListOfMaps(completionPayload, "contracts");
        this.laneCompletionSupport.requireText(completionPayload, "summary");
        this.laneCompletionSupport.requireText(completionPayload, "prUrl");
        this.laneCompletionSupport.requireText(completionPayload, "repo");
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final List<Map<String, Object>> contracts = this.laneCompletionSupport.requireListOfMaps(completionPayload, "contracts");
        final String summary = this.laneCompletionSupport.requireText(completionPayload, "summary");
        this.laneCompletionSupport.validateApiEvidence(lane, completionPayload, contracts);
        final LaneCompletionSupport.ExecutionContext context = this.laneCompletionSupport.buildApiExecutionContext(contracts);
        for (final Lane targetLane : this.laneCompletionSupport.findProducedImplementationLanes(lane.getLaneId())) {
            this.laneCompletionSupport.createApiImplementationTask(lane, summary, targetLane, context);
        }
    }
}
