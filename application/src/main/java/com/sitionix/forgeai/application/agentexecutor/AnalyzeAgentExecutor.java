package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.AnalyzerExecutionPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AnalyzerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;
@Log
@Component("analyzeAgentExecutor")
@RequiredArgsConstructor
public class AnalyzeAgentExecutor implements ExecuteAgent<AnalyzerPayload> {

    private final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;
    private final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;
    private final SupervisedExecutionProperties supervisedExecutionProperties;
    private final TicketRepository ticketRepository;
    private final CompleteAgentTasks completeAgentTasks;
    private final LaneCompletionSupport laneCompletionSupport;

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.executeClaimed(lane);
        final String ticket = this.ticketRepository.findTicketContentById(lane.getTicketId());

        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareAgentExecutionInputUseCase.enrichWithTasks(
                lane,
                input,
                Set.of(AnalyzerExecutionPayload.builder()
                        .ticket(ticket)
                        .build())
        );
        this.supervisedLaneExecutionUseCase.execute(lane, enrichedInput, this.supervisedExecutionProperties.getCorrectionAttempts());
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.requireExpectedScope(
                lane,
                Objects.toString(this.laneCompletionSupport.requireMap(completionPayload, "architectHandoff").get("scope"), lane.getScope())
        );
        this.laneCompletionSupport.requireExpectedScope(
                lane,
                Objects.toString(this.laneCompletionSupport.requireMap(completionPayload, "qaLeadHandoff").get("scope"), lane.getScope())
        );
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final AgentTicket<ArchitectPayload> architectTicket = this.laneCompletionSupport.ticket(
                lane,
                this.laneCompletionSupport.convert(this.laneCompletionSupport.requireMap(completionPayload, "architectHandoff"), ArchitectPayload.class),
                lane.getScope(),
                Agent.ARCHITECT
        );
        final AgentTicket<QaLeadPayload> qaLeadTicket = this.laneCompletionSupport.ticket(
                lane,
                this.laneCompletionSupport.convert(this.laneCompletionSupport.requireMap(completionPayload, "qaLeadHandoff"), QaLeadPayload.class),
                lane.getScope(),
                Agent.QA_LEAD
        );
        this.completeAgentTasks.complete(lane.getLaneId(), List.of(architectTicket, qaLeadTicket));
    }
}
