package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private final ObjectMapper objectMapper;
    private static final String TASKS_PLACEHOLDER = "{{TASKS_JSON}}";

    public String startPrompt(final ReadyToStartLane lane,
                              final LaneStrategy strategy,
                              final Set<String> sharedInstructionRefs) {
        final String sharedRefs = String.join("\n- ", sharedInstructionRefs);
        return "Supervised lane session started.\n"
                + "ticketId: " + lane.getTicketId() + "\n"
                + "laneId: " + lane.getLaneId() + "\n"
                + "agent: " + strategy.getAgentId() + "\n"
                + "Shared instruction refs:\n- " + sharedRefs;
    }

    public String stepPrompt(final LaneStrategyStep step,
                             final int index,
                             final int total,
                             final Set<AgentTicketPayload> tasks) {
        final String prompt = "Step " + index + "/" + total + "\n"
                + "Step id: " + step.getId() + "\n"
                + "Step title: " + step.getTitle() + "\n"
                + "Instruction refs:\n- " + String.join("\n- ", step.getInstructionRefs()) + "\n"
                + "Task payloads for this lane:\n"
                + TASKS_PLACEHOLDER + "\n"
                + "Active step id: " + step.getId();
        return prompt.replace(TASKS_PLACEHOLDER, this.serializeTasks(this.safeTasks(tasks)));
    }

    public String correctionPrompt(final String stepId) {
        return "Invalid step result. Return valid LANE_STEP_DONE for stepId=" + stepId + ".";
    }

    private String serializeTasks(final Set<AgentTicketPayload> tasks) {
        try {
            return this.objectMapper.writeValueAsString(tasks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payloads for step prompt", e);
        }
    }

    private Set<AgentTicketPayload> safeTasks(final Set<AgentTicketPayload> tasks) {
        return Objects.requireNonNullElseGet(tasks, Set::of);
    }
}
