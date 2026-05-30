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

    public String initialPrompt(final ReadyToStartLane lane, final LaneStrategy strategy) {
        return "Supervised lane session started.\n"
                + "ticketId: " + lane.getTicketId() + "\n"
                + "laneId: " + lane.getLaneId() + "\n"
                + "agent: " + strategy.getAgentId() + "\n"
                + "Wait for step prompts and execute one step at a time.\n"
                + "Only this completion schema is accepted:\n"
                + "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"<activeStepId>\",\"summary\":\"...\",\"evidence\":{}}";
    }

    public String stepPrompt(final LaneStrategyStep step,
                             final int index,
                             final int total,
                             final Set<AgentTicketPayload> tasks) {
        final StringBuilder prompt = new StringBuilder("You are executing lane step " + index + "/" + total + ".\n"
                + "Step id: " + step.getId() + "\n"
                + "Step title: " + step.getTitle() + "\n"
                + "Instruction refs for this step only:\n- " + String.join("\n- ", step.getInstructionRefs()) + "\n"
                + "Do not execute later steps yet.\n");
        if (tasks != null && !tasks.isEmpty()) {
            prompt.append("Task payloads for this lane:\n")
                    .append(this.serializeTasks(tasks))
                    .append("\n");
        }
        prompt.append("Return only valid JSON:\n")
                .append("{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"")
                .append(step.getId())
                .append("\",\"summary\":\"...\",\"evidence\":{}}");
        return prompt.toString();
    }

    public String correctionPrompt(final String stepId) {
        return "Your previous response did not match schema. Return only JSON:\n"
                + "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"" + stepId + "\",\"summary\":\"...\",\"evidence\":{}}";
    }

    private String serializeTasks(final Set<AgentTicketPayload> tasks) {
        try {
            return this.objectMapper.writeValueAsString(tasks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payloads for step prompt", e);
        }
    }
}
