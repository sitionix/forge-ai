package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ForgeAiContractApi;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.InstructionRepository;
import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.port.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PrepareAgentExecutionInputUseCase {

    private static final String FORGE_AI_ID = "forge-ai";
    private static final String API_KEY = "api";

    private final InstructionRepository instructionRepository;
    private final TicketRepository ticketRepository;
    private final ServicePropertiesProvider props;

    public AgentExecutionInput execute(final ReadyToStartLane lane) {
        final ServicePropertiesProvider.ServiceConfigView serviceConfigView = this.props.getServices().get(FORGE_AI_ID);
        final ServicePropertiesProvider.ContractRefView contractRefs = serviceConfigView.getContractRefs().get(API_KEY);

        final AgentInstructions instructions = this.instructionRepository.findInstructionsByAgentId(lane.getAgent().getId());
        this.ticketRepository.updateLaneStatus(lane.getLaneId(), LaneStatus.IN_PROGRESS);

        return AgentExecutionInput.builder()
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .agentInstruction(instructions.getAgentInstruction())
                .contractApi(ForgeAiContractApi.builder()
                        .path(contractRefs.getRoot())
                        .endpoint(instructions.getEndpoint())
                        .build())
                .additionalInstructions(instructions.getAdditionalInstructions())
                .sharedInstructions(instructions.getSharedInstructions())
                .build();
    }
}
