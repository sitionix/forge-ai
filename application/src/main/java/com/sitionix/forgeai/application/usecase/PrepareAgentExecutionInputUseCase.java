package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import static java.util.Objects.isNull;

@Component
@RequiredArgsConstructor
public class PrepareAgentExecutionInputUseCase {

    private final InstructionRepository instructionRepository;
    private final TicketRepository ticketRepository;
    private final ServicePropertiesProvider props;

    public AgentExecutionInput<AgentTicketPayload> execute(final ReadyToStartLane lane) {
        if (!this.ticketRepository.moveLaneToInProgressIfReady(lane.getLaneId())) {
            throw new IllegalStateException("Lane is not ready to start or already started: laneId=" + lane.getLaneId());
        }
        return this.executeClaimed(lane);
    }

    public AgentExecutionInput<AgentTicketPayload> executeClaimed(final ReadyToStartLane lane) {
        final AgentInstructions instructions = this.instructionRepository.findInstructionsByAgentId(lane.getAgent().getId());
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(lane.getTicketId())
                .ticket(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .agentInstruction(instructions.getAgentInstruction())
                .additionalInstructions(instructions.getAdditionalInstructions())
                .sharedInstructions(instructions.getSharedInstructions())
                .build();
    }

    public AgentExecutionInput<AgentTicketPayload> enrichWithTasks(
            final ReadyToStartLane lane,
            final AgentExecutionInput<AgentTicketPayload> input,
            final Set<? extends AgentTicketPayload> tasks
    ) {
        final ServicePropertiesProvider.ServiceConfigView serviceConfigView = this.props.getServices().get(lane.getServiceId());

        if (isNull(serviceConfigView)) {
            return input.toBuilder()
                    .tasks(new HashSet<>(tasks))
                    .scope(ScopeContext.builder()
                            .scope(lane.getScope())
                            .build())
                    .build();
        }

        return input.toBuilder()
                .tasks(new HashSet<>(tasks))
                .scope(ScopeContext.builder()
                        .scope(lane.getScope())
                        .label(serviceConfigView.getLabel())
                        .domainKeywords(new HashSet<>(serviceConfigView.getDomainKeywords()))
                        .tags(new HashSet<>(serviceConfigView.getTags()))
                        .ownBusinessAreas(new HashSet<>(serviceConfigView.getOwnsBusinessAreas()))
                        .build())
                .build();
    }
}
