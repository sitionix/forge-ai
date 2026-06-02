package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");
    private static final String TASKS_JSON_PLACEHOLDER = "TASKS_JSON";
    private static final String TASKS_PLACEHOLDER = "TASKS";
    private static final String TASK_PLACEHOLDER = "TASK";

    private final ObjectMapper objectMapper;
    private final InstructionRepository instructionRepository;

    public String startPrompt(final ReadyToStartLane lane,
                              final AgentExecutionInput<AgentTicketPayload> input,
                              final int totalSteps) {
        final Map<String, String> values = this.runtimeValues(lane, input, null, 0, totalSteps);
        return new StringBuilder()
                .append("Supervised lane session started.\n")
                .append("ticketId: ").append(lane.getTicketId()).append('\n')
                .append("laneId: ").append(lane.getLaneId()).append('\n')
                .append("agentId: ").append(lane.getAgent().getId()).append('\n')
                .append("scope: ").append(this.scopeValue(input)).append("\n\n")
                .append("Agent instruction:\n")
                .append(this.renderPlaceholders(Objects.toString(input.getAgentInstruction(), ""), values)).append("\n\n")
                .append("Additional instructions:\n")
                .append(this.renderInstructionBlock(input.getAdditionalInstructions(), values)).append("\n\n")
                .append("Shared instructions:\n")
                .append(this.renderInstructionBlock(input.getSharedInstructions(), values))
                .toString();
    }

    public String stepPrompt(final ReadyToStartLane lane,
                             final LaneStrategyStep step,
                             final int index,
                             final int total,
                             final AgentExecutionInput<AgentTicketPayload> input) {
        final Map<String, String> values = this.runtimeValues(lane, input, step, index, total);
        return new StringBuilder()
                .append("Step ").append(index).append('/').append(total).append('\n')
                .append("Step id: ").append(step.getId()).append('\n')
                .append("Step title: ").append(step.getTitle()).append('\n')
                .append("Instruction refs:\n")
                .append(this.renderInstructionRefs(step.getInstructionRefs(), values)).append("\n\n")
                .append("Task payloads for this lane:\n")
                .append(this.renderPlaceholders(this.serializeTasks(this.safeTasks(input.getTasks())), values)).append('\n')
                .append("Active step id: ").append(step.getId()).append('\n')
                .append("When the step is complete, return the common LANE_STEP_DONE result block for this step id.")
                .toString();
    }

    public String correctionPrompt(final String stepId) {
        return "Your previous response did not contain a valid LANE_STEP_DONE result for stepId=" + stepId + ".\n"
                + "Return one valid marked LANE_STEP_DONE JSON block for the current step.\n"
                + "Do not continue to another step.";
    }

    private String renderInstructionBlock(final Set<String> instructions, final Map<String, String> values) {
        if (instructions == null || instructions.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        int index = 0;
        for (final String instruction : instructions) {
            if (index++ > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append(this.renderPlaceholders(instruction, values));
        }
        return builder.toString();
    }

    private String renderInstructionRefs(final Iterable<String> refs, final Map<String, String> values) {
        final StringBuilder builder = new StringBuilder();
        int index = 0;
        for (final String ref : refs) {
            if (index++ > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append(this.renderPlaceholders(this.instructionRepository.findInstructionTextByRef(ref), values));
        }
        return builder.toString();
    }

    private Map<String, String> runtimeValues(final ReadyToStartLane lane,
                                              final AgentExecutionInput<AgentTicketPayload> input,
                                              final LaneStrategyStep step,
                                              final int index,
                                              final int total) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(TASKS_JSON_PLACEHOLDER, this.serializeTasks(this.safeTasks(input.getTasks())));
        values.put(TASKS_PLACEHOLDER, values.get(TASKS_JSON_PLACEHOLDER));
        values.put(TASK_PLACEHOLDER, values.get(TASKS_JSON_PLACEHOLDER));
        values.put(TASKS_JSON_PLACEHOLDER.toLowerCase(), values.get(TASKS_JSON_PLACEHOLDER));
        values.put(TASKS_PLACEHOLDER.toLowerCase(), values.get(TASKS_JSON_PLACEHOLDER));
        values.put(TASK_PLACEHOLDER.toLowerCase(), values.get(TASKS_JSON_PLACEHOLDER));
        values.put("TICKET_ID", Objects.toString(lane.getTicketId(), ""));
        values.put("LANE_ID", Objects.toString(lane.getLaneId(), ""));
        values.put("AGENT_ID", Objects.toString(lane.getAgent().getId(), ""));
        values.put("SCOPE", this.scopeValue(input));
        values.put("STEP_INDEX", Integer.toString(index));
        values.put("STEP_TOTAL", Integer.toString(total));
        if (step != null) {
            values.put("STEP_ID", Objects.toString(step.getId(), ""));
            values.put("STEP_TITLE", Objects.toString(step.getTitle(), ""));
        }
        final Map<String, String> aliases = new LinkedHashMap<>();
        values.forEach((key, value) -> aliases.putIfAbsent(key.toLowerCase(), value));
        values.putAll(aliases);
        return values;
    }

    private String renderPlaceholders(final String template, final Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        final Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        final StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            final String key = matcher.group(1);
            final String replacement = values.get(key);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String scopeValue(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input.getScope() == null || input.getScope().getScope() == null) {
            return "";
        }
        return input.getScope().getScope();
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
