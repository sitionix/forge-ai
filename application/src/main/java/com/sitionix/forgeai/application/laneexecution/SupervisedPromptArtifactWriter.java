package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SupervisedPromptArtifactWriter {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    private final ObjectMapper objectMapper;
    private final InstructionRepository instructionRepository;
    private final LaneStrategyPromptConfig laneStrategyPromptConfig;
    private final SupervisedExecutionProperties supervisedExecutionProperties;

    public Path writeStartContext(final ReadyToStartLane lane,
                                  final LaneStrategy strategy,
                                  final AgentExecutionInput<AgentTicketPayload> input,
                                  final UUID executionId) {
        final Path executionDirectory = this.executionDirectory(lane, executionId);
        this.createDirectories(executionDirectory.resolve("steps"));
        final Path startContextPath = executionDirectory.resolve("start-context.json");
        this.writeText(startContextPath, this.serializeStartContext(lane, strategy, input, executionId));
        return startContextPath;
    }

    public Path writeStepInstructionFile(final ReadyToStartLane lane,
                                        final LaneStrategyStep step,
                                        final AgentExecutionInput<AgentTicketPayload> input,
                                        final UUID executionId) {
        final Path executionDirectory = this.executionDirectory(lane, executionId);
        this.createDirectories(executionDirectory.resolve("steps"));
        final Path stepPath = executionDirectory.resolve("steps").resolve(step.getOrder() + "-" + step.getId() + ".md");
        final String renderedContent = this.renderStepContent(step, input);
        this.writeText(stepPath, renderedContent);
        return stepPath;
    }

    public Path executionDirectory(final ReadyToStartLane lane, final UUID executionId) {
        return this.runtimeRoot()
                .resolve(this.safeFileSegment(lane.getTicketKey()))
                .resolve(this.safeFileSegment(Objects.toString(lane.getLaneId(), "")))
                .resolve(executionId.toString());
    }

    private Path runtimeRoot() {
        return Path.of(this.supervisedExecutionProperties.getRuntimeRoot()).toAbsolutePath().normalize();
    }

    private String serializeStartContext(final ReadyToStartLane lane,
                                         final LaneStrategy strategy,
                                         final AgentExecutionInput<AgentTicketPayload> input,
                                         final UUID executionId) {
        final Map<String, Object> context = new LinkedHashMap<>();
        context.put("executionId", executionId.toString());
        context.put("ticketId", lane.getTicketId());
        context.put("ticketKey", lane.getTicketKey());
        context.put("laneId", lane.getLaneId());
        context.put("agentId", lane.getAgent().getId());
        context.put("scope", lane.getScope());
        context.put("workspaceRoot", this.workspaceRoot());
        context.put("strategyId", strategy.getAgentId());
        context.put("strategyVersion", strategy.getVersion());
        context.put("sessionMode", strategy.getSessionMode());
        context.put("commonInstructionRefs", this.laneStrategyPromptConfig.getCommonInstructionRefs());
        context.put("scopeContext", input == null ? null : input.getScope());
        context.put("contractApi", input == null ? null : input.getContractApi());
        context.put("tasks", input == null || input.getTasks() == null ? List.of() : new ArrayList<>(input.getTasks()));
        context.put("generatedAt", LocalDateTime.now().toString());
        return this.writeJson(context);
    }

    private String renderStepContent(final LaneStrategyStep step, final AgentExecutionInput<AgentTicketPayload> input) {
        final Map<String, String> values = this.stepValues(step, input);
        final StringBuilder builder = new StringBuilder();
        int index = 0;
        for (final String ref : step.getInstructionRefs()) {
            if (index++ > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append(this.renderPlaceholders(this.instructionRepository.findInstructionTextByRef(ref), values));
        }
        return builder.toString().trim();
    }

    private Map<String, String> stepValues(final LaneStrategyStep step,
                                           final AgentExecutionInput<AgentTicketPayload> input) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("STEP_ID", Objects.toString(step.getId(), ""));
        values.put("STEP_TITLE", Objects.toString(step.getTitle(), ""));
        if (input != null) {
            values.put("TICKET_ID", Objects.toString(input.getTicketId(), ""));
            values.put("TICKET_KEY", Objects.toString(input.getTicket(), ""));
            values.put("LANE_ID", Objects.toString(input.getLaneId(), ""));
            values.put("SCOPE", this.serializeScopeContext(input.getScope()));
            values.put("SCOPE_CONTEXT", this.serializeScopeContext(input.getScope()));
            values.put("CONTRACT_API", this.serializeContractApi(input));
        }
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

    private String serializeTasks(final Set<AgentTicketPayload> tasks) {
        try {
            return this.objectMapper.writeValueAsString(tasks);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task payloads for supervised step file", e);
        }
    }

    private String serializeScopeContext(final ScopeContext scopeContext) {
        if (scopeContext == null) {
            return "";
        }
        try {
            return this.objectMapper.writeValueAsString(scopeContext);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize scope context for supervised step file", e);
        }
    }

    private String serializeContractApi(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input.getContractApi() == null) {
            return "";
        }
        try {
            return this.objectMapper.writeValueAsString(input.getContractApi());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize contract api for supervised step file", e);
        }
    }

    private String writeJson(final Map<String, Object> value) {
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize supervised start context", e);
        }
    }

    private void writeText(final Path path, final String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to write supervised prompt artifact: " + path, e);
        }
    }

    private void createDirectories(final Path path) {
        try {
            Files.createDirectories(path);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to create supervised prompt artifact directory: " + path, e);
        }
    }

    private Path safeFileSegment(final String value) {
        return Path.of(this.safeSegment(value));
    }

    private String safeSegment(final String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
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
