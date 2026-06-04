package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private static final String RESULT_CONTRACT = """
            Return exactly one JSON object with no markdown fences and no text before or after it:
            {
              "type": "LANE_STEP_DONE",
              "stepId": "<active step id>",
              "summary": "<non-empty summary>",
              "evidence": {}
            }

            Rules:
            - type must equal LANE_STEP_DONE
            - stepId must equal the active step id
            - summary must be a non-empty string
            - evidence must be a JSON object
            - nested evidence objects and arrays are allowed
            - forbidden top-level fields: status, failed, blocked, skipped, needsFix, error
            - no extra top-level fields
            """;

    private final LaneStrategyPromptConfig laneStrategyPromptConfig;
    private final InstructionRepository instructionRepository;
    private final LaneRepository laneRepository;
    private final LaneCompletionContractResolver laneCompletionContractResolver;
    private final ObjectMapper objectMapper;

    public String buildStartPrompt(final ReadyToStartLane lane,
                                   final LaneStrategy strategy,
                                   final AgentExecutionInput<AgentTicketPayload> input) {
        final StringBuilder prompt = new StringBuilder()
                .append("START_PROMPT\n\n")
                .append("Execution metadata:\n")
                .append("- ticketId: ").append(lane.getTicketId()).append('\n')
                .append("- ticketKey: ").append(lane.getTicketKey()).append('\n')
                .append("- laneId: ").append(lane.getLaneId()).append('\n')
                .append("- agentId: ").append(lane.getAgent().getId()).append('\n')
                .append("- scope: ").append(lane.getScope()).append('\n')
                .append("- strategyId: ").append(strategy.getAgentId()).append('\n')
                .append("- strategyVersion: ").append(strategy.getVersion()).append('\n')
                .append("- sessionMode: ").append(strategy.getSessionMode()).append('\n')
                .append('\n')
                .append("Task payloads:\n")
                .append(this.renderTasks(input))
                .append('\n')
                .append('\n')
                .append("Scope context:\n")
                .append(this.renderScopeContext(input))
                .append('\n')
                .append('\n')
                .append("Common instructions:\n")
                .append(this.renderResolvedInstructions(this.laneStrategyPromptConfig.getCommonInstructionRefs()))
                .append('\n')
                .append('\n')
                .append("JSON result contract:\n")
                .append(RESULT_CONTRACT.trim());
        return prompt.toString().trim();
    }

    public String buildStepPrompt(final ReadyToStartLane lane,
                                  final LaneStrategy strategy,
                                  final LaneStrategyStep step,
                                  final AgentExecutionInput<AgentTicketPayload> input,
                                  final int stepIndex,
                                  final int totalSteps) {
        final StringBuilder prompt = new StringBuilder()
                .append("STEP_PROMPT\n\n")
                .append("Current step:\n")
                .append("- stepIndex: ").append(stepIndex).append('\n')
                .append("- totalSteps: ").append(totalSteps).append('\n')
                .append("- stepId: ").append(step.getId()).append('\n')
                .append("- stepTitle: ").append(step.getTitle()).append('\n')
                .append('\n')
                .append("Assigned lane context:\n")
                .append("- ticketId: ").append(lane.getTicketId()).append('\n')
                .append("- ticketKey: ").append(lane.getTicketKey()).append('\n')
                .append("- laneId: ").append(lane.getLaneId()).append('\n')
                .append("- agentId: ").append(lane.getAgent().getId()).append('\n')
                .append("- scope: ").append(lane.getScope()).append('\n')
                .append('\n')
                .append("Task payloads:\n")
                .append(this.renderTasks(input))
                .append('\n')
                .append('\n')
                .append("Scope context:\n")
                .append(this.renderScopeContext(input))
                .append('\n')
                .append('\n')
                .append("Active step instructions:\n")
                .append(this.renderResolvedInstructions(step.getInstructionRefs()))
                .append('\n');
        if (stepIndex == totalSteps) {
            prompt.append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.finalCompletionPayloadContract(lane))
                    .append('\n');
        }
        prompt.append('\n')
                .append("Execute only this step. Return only the JSON object.");
        return prompt.toString().trim();
    }

    public String buildCorrectionPrompt(final ReadyToStartLane lane,
                                        final LaneStrategyStep step,
                                        final String validationError,
                                        final boolean finalStep) {
        final StringBuilder prompt = new StringBuilder()
                .append("CORRECTION_PROMPT\n\n")
                .append("Active step id: ").append(step.getId()).append('\n')
                .append("Active step title: ").append(step.getTitle()).append('\n')
                .append("Validation error: ").append(Objects.toString(validationError, "invalid response")).append('\n')
                .append('\n')
                .append("Return only one corrected JSON object. No prose. No markdown fences.");
        if (finalStep) {
            prompt.append('\n')
                    .append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.finalCompletionPayloadContract(lane));
        }
        return prompt.toString().trim();
    }

    private String renderResolvedInstructions(final Iterable<String> refs) {
        final StringBuilder builder = new StringBuilder();
        for (final String ref : refs) {
            builder.append("### ").append(ref).append('\n')
                    .append(this.instructionRepository.findInstructionTextByRef(ref).trim())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String renderTasks(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input == null || input.getTasks() == null || input.getTasks().isEmpty()) {
            return "[]";
        }
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input.getTasks());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render task payloads", e);
        }
    }

    private String renderScopeContext(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input == null || input.getScope() == null) {
            return "{}";
        }
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input.getScope());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render scope context", e);
        }
    }

    private String finalCompletionPayloadContract(final ReadyToStartLane lane) {
        final StringBuilder builder = new StringBuilder()
                .append("evidence.completionPayload must be a JSON object with this shape:\n")
                .append("{\n")
                .append("  \"outputs\": [\n")
                .append(this.renderProducedOutputContracts(lane))
                .append("  ]");
        if (this.requiresApiCompletionEvidence(lane.getAgent())) {
            builder.append(",\n")
                    .append("  \"apiEvidence\": {\n")
                    .append("    \"summary\": \"...\",\n")
                    .append("    \"prUrl\": \"https://github.com/owner/repo/pull/123\",\n")
                    .append("    \"repo\": \"owner/repo\",\n")
                    .append("    \"contracts\": []\n")
                    .append("  }");
        }
        if (this.completionReportPayloadType(lane.getAgent()).isPresent()) {
            builder.append(",\n")
                    .append("  \"report\": ")
                    .append(this.renderPayloadShape(this.completionReportPayloadType(lane.getAgent()).get()));
        }
        builder.append("\n}\n\n")
                .append("Output rules:\n")
                .append(this.outputRuleFor(lane))
                .append("- agent and scope must exactly match the produced lane.\n")
                .append("- required=false marks that produced lane as not needed.\n")
                .append("- required=true requires payload to match the listed payload type.\n")
                .append("- do not invent agents or scopes that are not listed.");
        return builder.toString().trim();
    }

    private String outputRuleFor(final ReadyToStartLane lane) {
        final List<Lane> producedLanes = this.laneRepository.findCompletionTargetLanes(lane.getLaneId());
        if (producedLanes.isEmpty() || !this.writesProducedLaneOutputs(lane.getAgent())) {
            return "- outputs must be an empty array for this lane.\n";
        }
        if (!this.requiresCompletionOutputForEveryTarget(lane.getAgent())) {
            return "- outputs may contain entries only for produced lanes that need downstream input from this completion.\n";
        }
        return "- outputs must contain exactly one entry for every produced lane listed below.\n";
    }

    private String renderProducedOutputContracts(final ReadyToStartLane lane) {
        final List<Lane> producedLanes = this.laneRepository.findCompletionTargetLanes(lane.getLaneId());
        if (producedLanes.isEmpty() || !this.writesProducedLaneOutputs(lane.getAgent())) {
            return "";
        }
        return producedLanes.stream()
                .map(targetLane -> this.renderProducedOutputContract(lane.getAgent(), targetLane))
                .collect(Collectors.joining(",\n"));
    }

    private String renderProducedOutputContract(final Agent sourceAgent, final Lane targetLane) {
        final Class<? extends AgentTicketPayload> payloadType =
                this.laneCompletionContractResolver.inputPayloadType(sourceAgent, targetLane.getAgent());
        return """
                    {
                      "agent": "%s",
                      "scope": "%s",
                      "required": true,
                      "payload": %s
                    }""".formatted(
                targetLane.getAgent().getId(),
                targetLane.getScope(),
                this.renderPayloadShape(payloadType)
        );
    }

    private boolean writesProducedLaneOutputs(final Agent agent) {
        return this.laneCompletionContractResolver.writesProducedLaneOutputs(agent);
    }

    private boolean requiresApiCompletionEvidence(final Agent agent) {
        return this.laneCompletionContractResolver.requiresApiCompletionEvidence(agent);
    }

    private boolean requiresCompletionOutputForEveryTarget(final Agent agent) {
        return this.laneCompletionContractResolver.requiresCompletionOutputForEveryTarget(agent);
    }

    private Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType(final Agent agent) {
        return this.laneCompletionContractResolver.completionReportPayloadType(agent);
    }

    private String renderPayloadShape(final Class<? extends AgentTicketPayload> payloadType) {
        final Map<String, Object> fields = Arrays.stream(payloadType.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toMap(
                        Field::getName,
                        this::sampleValueFor,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fields);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render payload shape for " + payloadType.getName(), e);
        }
    }

    private Object sampleValueFor(final Field field) {
        if (String.class.equals(field.getType())) {
            return "...";
        }
        if (Boolean.class.equals(field.getType()) || boolean.class.equals(field.getType())) {
            return true;
        }
        if (Number.class.isAssignableFrom(field.getType()) || field.getType().isPrimitive()) {
            return 0;
        }
        if (Set.class.isAssignableFrom(field.getType()) || Iterable.class.isAssignableFrom(field.getType())) {
            return List.of();
        }
        return Map.of();
    }
}
