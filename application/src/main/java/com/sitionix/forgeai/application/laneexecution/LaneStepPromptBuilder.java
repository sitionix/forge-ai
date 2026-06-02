package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.nio.file.Path;
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

    private final ObjectMapper objectMapper;
    private final InstructionRepository instructionRepository;

    public String buildStartPrompt(final ReadyToStartLane lane,
                                   final LaneStrategy strategy,
                                   final AgentExecutionInput<AgentTicketPayload> input) {
        final StringBuilder builder = new StringBuilder("START_PROMPT\n");
        this.appendSection(builder, "ticketId", Objects.toString(lane.getTicketId(), ""));
        this.appendSection(builder, "ticketKey", Objects.toString(lane.getTicketKey(), ""));
        this.appendSection(builder, "laneId", Objects.toString(lane.getLaneId(), ""));
        this.appendSection(builder, "agentId", Objects.toString(lane.getAgent().getId(), ""));
        this.appendSection(builder, "scope", Objects.toString(lane.getScope(), ""));
        this.appendSection(builder, "strategyId", Objects.toString(strategy.getAgentId(), ""));
        this.appendSection(builder, "strategyVersion", Integer.toString(strategy.getVersion()));
        this.appendSection(builder, "workspaceRoot", this.workspaceRoot());
        this.appendSection(builder, "scopeContext", this.serializeScopeContext(input.getScope()));
        this.appendSection(builder, "contractApi", this.serializeContractApi(input));
        this.appendSection(builder, "commonRules", this.renderCommonRules());
        return builder.toString().trim();
    }

    public String buildStepPrompt(final LaneStrategyStep step,
                                  final int index,
                                  final int total) {
        return this.buildStepPrompt(step, index, total, null, null, null);
    }

    public String buildStepPrompt(final LaneStrategyStep step,
                                  final int index,
                                  final int total,
                                  final ReadyToStartLane lane,
                                  final LaneStrategy strategy,
                                  final AgentExecutionInput<AgentTicketPayload> input) {
        final Map<String, String> values = this.stepValues(step, input);
        final StringBuilder builder = new StringBuilder("STEP_PROMPT\n");
        this.appendSection(builder, "stepIndex", index + "/" + total);
        this.appendSection(builder, "stepId", step.getId());
        this.appendSection(builder, "stepTitle", step.getTitle());
        if (step.getTaskPlaceholder() != null && !step.getTaskPlaceholder().isBlank()) {
            this.appendSection(builder, "taskPayloads", this.serializeTasks(this.safeTasks(input == null ? null : input.getTasks())));
        }
        this.appendSection(builder, "activeInstructions", this.renderInstructionRefs(step.getInstructionRefs(), values));
        builder.append("Return the common LANE_STEP_DONE result block for this step id.");
        return builder.toString().trim();
    }

    public String buildCorrectionPrompt(final String stepId) {
        return "CORRECTION_PROMPT\n"
                + "Your previous response did not contain a valid LANE_STEP_DONE result for stepId=" + stepId + ".\n"
                + "Return one valid marked LANE_STEP_DONE JSON block for the current step.\n"
                + "Do not continue to another step.";
    }

    private String renderCommonRules() {
        return this.instructionRepository.findInstructionTextByRef("shared/common-rules.md");
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

    private Map<String, String> stepValues(final LaneStrategyStep step,
                                           final AgentExecutionInput<AgentTicketPayload> input) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("STEP_ID", Objects.toString(step.getId(), ""));
        values.put("STEP_TITLE", Objects.toString(step.getTitle(), ""));
        if (step.getTaskPlaceholder() != null && !step.getTaskPlaceholder().isBlank() && input != null) {
            values.put("TASKS_JSON", this.serializeTasks(this.safeTasks(input.getTasks())));
            values.put("TASKS", values.get("TASKS_JSON"));
            values.put("TASK", values.get("TASKS_JSON"));
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

    private void appendSection(final StringBuilder builder, final String label, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(label).append(":\n").append(value.trim()).append("\n\n");
    }

    private String serializeTasks(final Set<AgentTicketPayload> tasks) {
        try {
            return this.objectMapper.writeValueAsString(tasks);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payloads for step prompt", e);
        }
    }

    private String serializeScopeContext(final ScopeContext scopeContext) {
        if (scopeContext == null) {
            return "";
        }
        try {
            return this.objectMapper.writeValueAsString(scopeContext);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize scope context", e);
        }
    }

    private String serializeContractApi(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input.getContractApi() == null) {
            return "";
        }
        try {
            return this.objectMapper.writeValueAsString(input.getContractApi());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize contract api", e);
        }
    }

    private String workspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return Path.of(envWorkspaceRoot).toAbsolutePath().normalize().toString();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
    }

    private Set<AgentTicketPayload> safeTasks(final Set<AgentTicketPayload> tasks) {
        return Objects.requireNonNullElseGet(tasks, Set::of);
    }
}
