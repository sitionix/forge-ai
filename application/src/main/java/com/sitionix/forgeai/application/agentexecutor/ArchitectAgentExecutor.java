package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
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
@Component("architectAgentExecutor")
public class ArchitectAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<ArchitectPayload> {

    public ArchitectAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
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
        log.info("Execute architect lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.requireExpectedScope(lane, this.laneCompletionSupport.requireText(completionPayload, "implementationScope"));
        if (this.laneCompletionSupport.requireBoolean(completionPayload, "apiRequired")) {
            this.laneCompletionSupport.requireMap(completionPayload, "apiRequest");
        }
        if (this.laneCompletionSupport.requireBoolean(completionPayload, "eventRequired")) {
            this.laneCompletionSupport.requireMap(completionPayload, "eventRequest");
        }
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final String implementationScope = this.laneCompletionSupport.requireText(completionPayload, "implementationScope");
        this.laneCompletionSupport.requireExpectedScope(lane, implementationScope);
        final Agent implementationAgent = this.laneCompletionSupport.resolveImplementationAgent(implementationScope);
        if (Agent.IMPLEMENT_BE.equals(implementationAgent)) {
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(
                    this.laneCompletionSupport.ticket(
                            lane,
                            this.laneCompletionSupport.convert(this.laneCompletionSupport.requireMap(completionPayload, "implementationHandoff"), ImplementBePayload.class),
                            implementationScope,
                            Agent.IMPLEMENT_BE
                    )
            ));
        } else {
            this.completeAgentTasks.complete(lane.getLaneId(), List.of(
                    this.laneCompletionSupport.ticket(
                            lane,
                            this.laneCompletionSupport.convert(this.laneCompletionSupport.requireMap(completionPayload, "implementationHandoff"), ImplementFePayload.class),
                            implementationScope,
                            Agent.IMPLEMENT_FE
                    )
            ));
        }
        this.laneCompletionSupport.createOptionalArchitectLaneTask(
                lane, completionPayload, "apiRequired", Agent.API, this.laneCompletionSupport.globalScope(), "apiRequest",
                ApiPayload.class
        );
        this.laneCompletionSupport.createOptionalArchitectLaneTask(
                lane, completionPayload, "eventRequired", Agent.EVENT, this.laneCompletionSupport.globalScope(), "eventRequest",
                EventPayload.class
        );
    }
}
