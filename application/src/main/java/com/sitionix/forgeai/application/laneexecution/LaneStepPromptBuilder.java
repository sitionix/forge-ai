package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private final ObjectMapper objectMapper;

    public String startStepPrompt(final ReadyToStartLane lane,
                                  final LaneStrategy strategy,
                                  final LaneStrategyStep step,
                                  final int index,
                                  final int total,
                                  final Set<AgentTicketPayload> tasks) {
        final StringBuilder prompt = new StringBuilder("Supervised lane session started.\n"
                + "ticketId: " + lane.getTicketId() + "\n"
                + "laneId: " + lane.getLaneId() + "\n"
                + "agent: " + strategy.getAgentId() + "\n"
                + "You are executing lane step " + index + "/" + total + ".\n"
                + "Step id: " + step.getId() + "\n"
                + "Step title: " + step.getTitle() + "\n"
                + "Instruction refs for this step only:\n- " + String.join("\n- ", step.getInstructionRefs()) + "\n"
                + "Do not execute later steps yet.\n");
        if (tasks != null && !tasks.isEmpty()) {
            prompt.append("Task payloads for this lane:\n")
                    .append(this.serializeTasks(tasks))
                    .append("\n");
        }
        prompt.append("Follow LANE_STEP_DONE schema from shared/common-rules.md.\n")
                .append("Return only LANE_STEP_DONE JSON for active step id `")
                .append(step.getId())
                .append("` as defined in shared/common-rules.md.");
        return prompt.toString();
    }

    public String correctionPrompt(final String stepId) {
        return "Your previous response did not match the LANE_STEP_DONE schema from shared/common-rules.md. "
                + "Return only LANE_STEP_DONE JSON for active step id `" + stepId + "`.";
    }

    private String serializeTasks(final Set<AgentTicketPayload> tasks) {
        try {
            return this.objectMapper.writeValueAsString(tasks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payloads for step prompt", e);
        }
    }
}
