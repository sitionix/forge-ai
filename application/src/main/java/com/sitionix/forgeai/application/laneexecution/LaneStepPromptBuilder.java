package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private static final String TASKS_PLACEHOLDER = "TASKS";
    private static final String COMPLETION_PAYLOAD_CONTRACT_PLACEHOLDER = "COMPLETION_PAYLOAD_CONTRACT";

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
    private final CompletionPayloadContractBuilder completionPayloadContractBuilder;
    private final CompletionPayloadContractRenderer completionPayloadContractRenderer;
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
                .append('\n');
        this.appendTaskPlaceholder(prompt, step, input);
        prompt.append("Active step instructions:\n")
                .append(this.renderResolvedInstructions(step.getInstructionRefs()))
                .append('\n');
        if (stepIndex == totalSteps && this.shouldRenderCompletionPayloadContract(step)) {
            prompt.append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.completionPayloadContractRenderer.render(this.completionPayloadContractBuilder.build(lane)))
                    .append('\n');
        }
        prompt.append('\n')
                .append("Execute only this step. Return only the JSON object.");
        return prompt.toString().trim();
    }

    private void appendTaskPlaceholder(final StringBuilder prompt,
                                       final LaneStrategyStep step,
                                       final AgentExecutionInput<AgentTicketPayload> input) {
        if (step.getTaskPlaceholder() == null || step.getTaskPlaceholder().isBlank()) {
            return;
        }
        if (!Objects.equals(TASKS_PLACEHOLDER, step.getTaskPlaceholder())) {
            throw new IllegalArgumentException("Unsupported lane step task placeholder: " + step.getTaskPlaceholder()
                    + ", stepId=" + step.getId());
        }
        prompt.append("Task payloads:\n")
                .append(this.renderTasks(input))
                .append('\n')
                .append('\n');
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
        if (finalStep && this.shouldRenderCompletionPayloadContract(step)) {
            prompt.append('\n')
                    .append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.completionPayloadContractRenderer.render(this.completionPayloadContractBuilder.build(lane)));
        }
        return prompt.toString().trim();
    }

    private boolean shouldRenderCompletionPayloadContract(final LaneStrategyStep step) {
        if (step.getCompletionContractPlaceholder() == null || step.getCompletionContractPlaceholder().isBlank()) {
            return false;
        }
        if (!Objects.equals(COMPLETION_PAYLOAD_CONTRACT_PLACEHOLDER, step.getCompletionContractPlaceholder())) {
            throw new IllegalArgumentException("Unsupported completion contract placeholder: " + step.getCompletionContractPlaceholder()
                    + ", stepId=" + step.getId());
        }
        return true;
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

}
