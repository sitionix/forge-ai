package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.Map;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("qaLeadAgentExecutor")
public class QaLeadAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<QaLeadPayload> {

    public QaLeadAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
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
        log.info("Execute qa_lead lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.requireExpectedScope(lane, this.laneCompletionSupport.requireText(completionPayload, "scope"));
        if (this.laneCompletionSupport.requireBoolean(completionPayload, "unitTestRequired")) {
            this.laneCompletionSupport.requireMap(completionPayload, "testUnitPayload");
        }
        if (this.laneCompletionSupport.requireBoolean(completionPayload, "integrationTestRequired")) {
            this.laneCompletionSupport.requireMap(completionPayload, "testItPayload");
        }
        if (this.laneCompletionSupport.requireBoolean(completionPayload, "uiTestRequired")) {
            this.laneCompletionSupport.requireMap(completionPayload, "testUiPayload");
        }
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.requireExpectedScope(lane, this.laneCompletionSupport.requireText(completionPayload, "scope"));
        this.laneCompletionSupport.routeQaTestLane(lane, completionPayload, "unitTestRequired", Agent.TEST_UNIT, "testUnitPayload", QaLeadTestUnitPayload.class);
        this.laneCompletionSupport.routeQaTestLane(lane, completionPayload, "integrationTestRequired", Agent.TEST_IT, "testItPayload", QaLeadTestItPayload.class);
        this.laneCompletionSupport.routeQaTestLane(lane, completionPayload, "uiTestRequired", Agent.TEST_UI, "testUiPayload", QaLeadTestUiPayload.class);
    }
}
