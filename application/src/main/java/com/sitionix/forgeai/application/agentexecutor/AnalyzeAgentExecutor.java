package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.port.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.util.HashSet;

@Log
@Component("analyzeAgentExecutor")
@RequiredArgsConstructor
public class AnalyzeAgentExecutor implements ExecuteAgent {

    private final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    private final ServicePropertiesProvider props;

    private final CodexClient codexClient;

    private final TicketRepository ticketRepository;

    @Override
    public void execute(final ReadyToStartLane lane) {
        final AgentExecutionInput input = this.prepareAgentExecutionInputUseCase.execute(lane);
        final ServicePropertiesProvider.ServiceConfigView serviceConfigView = this.props.getServices().get(lane.getServiceId());
        final String ticket = this.ticketRepository.findTicketContentById(lane.getTicketId());

        final AgentExecutionInput enrichedInput = input.toBuilder()
                .ticket(ticket)
                .scope(ScopeContext.builder()
                        .scope(lane.getScope())
                        .label(serviceConfigView.getLabel())
                        .domainKeywords(new HashSet<>(serviceConfigView.getDomainKeywords()))
                        .tags(new HashSet<>(serviceConfigView.getTags()))
                        .ownBusinessAreas(new HashSet<>(serviceConfigView.getOwnsBusinessAreas()))
                        .build())
                .build();


        log.info("Execute analyzer lane with input: " + enrichedInput);

        this.codexClient.submit(enrichedInput, lane.getSourceTerminalTty());
    }
}
