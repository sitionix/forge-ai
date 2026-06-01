package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.AnalyzerExecutionPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AnalyzerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.port.CodexClient;
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

    private final CodexClient codexClient;

    private final TicketRepository ticketRepository;
    private final SupervisedExecutionProperties supervisedExecutionProperties;
    private final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.execute(lane);
        final String ticket = this.ticketRepository.findTicketContentById(lane.getTicketId());

        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareAgentExecutionInputUseCase.enrichWithTasks(
                lane,
                input,
                Set.of(AnalyzerExecutionPayload.builder()
                        .ticket(ticket)
                        .build())
        );
        if (this.supervisedExecutionProperties.isSupervisedAgent(lane.getAgent().getId())) {
            this.supervisedLaneExecutionUseCase.execute(lane, enrichedInput, this.supervisedExecutionProperties.getCorrectionAttempts());
            return;
        }
        this.codexClient.submit(enrichedInput, lane.getSourceTerminalTty());
    }
}
