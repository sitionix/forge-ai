package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupervisedLaneExecutionUseCase {

    private static final Logger log = Logger.getLogger(SupervisedLaneExecutionUseCase.class.getName());
    private static final long STEP_OUTPUT_TIMEOUT_MS = 120_000L;

    private final LaneStrategyRepository laneStrategyRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final CodexSessionRepository codexSessionRepository;
    private final LaneStepDoneResultParser parser;
    private final LaneStepPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public void execute(final ReadyToStartLane lane,
                        final AgentExecutionInput<AgentTicketPayload> input,
                        final int correctionAttempts) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final LaneStrategyStep firstStep = strategy.getSteps().getFirst();
        final String startPrompt = this.promptBuilder.buildStartPrompt(lane, strategy, input);
        final String firstStepPrompt = this.promptBuilder.buildStepPrompt(firstStep, 1, strategy.getSteps().size(), lane, strategy, input);
        final String initialPrompt = startPrompt
                + "\n\n"
                + firstStepPrompt;
        final String sessionId = this.codexSessionRepository.start(initialPrompt, lane.getSourceTerminalTty());
        final LaneExecution execution = this.createExecution(lane, strategy, sessionId);

        this.logPromptSent("START_PROMPT", startPrompt, lane, execution.getId(), sessionId, firstStep.getId(), true);
        this.logPromptSent("STEP_PROMPT", firstStepPrompt, lane, execution.getId(), sessionId, firstStep.getId(), true);
        this.logBoundarySet("START_PROMPT", initialPrompt, lane, execution.getId(), sessionId, firstStep.getId(), true);
        this.logEvent("supervised.execution.started", lane, execution.getId(), sessionId, null);
        this.logEvent("codex.session.started", lane, execution.getId(), sessionId, null);

        try {
            this.runSteps(lane, input, strategy, execution, sessionId, initialPrompt, correctionAttempts);
            this.logEvent("supervised.execution.steps.completed", lane, execution.getId(), sessionId, null);
        } finally {
            this.codexSessionRepository.close(sessionId);
            this.logEvent("codex.session.closed", lane, execution.getId(), sessionId, null);
        }
    }

    private void runSteps(final ReadyToStartLane lane,
                          final AgentExecutionInput<AgentTicketPayload> input,
                          final LaneStrategy strategy,
                          final LaneExecution execution,
                          final String sessionId,
                          final String initialPrompt,
                          final int correctionAttempts) {
        LaneExecution currentExecution = execution;
        for (int index = 0; index < strategy.getSteps().size(); index++) {
            final LaneStrategyStep step = strategy.getSteps().get(index);
            currentExecution = currentExecution.toBuilder()
                    .currentStepId(step.getId())
                    .updatedAt(LocalDateTime.now())
                    .build();
            this.laneExecutionRepository.updateCurrentStep(currentExecution);
            this.logEvent("supervised.step.started", lane, currentExecution.getId(), sessionId, step.getId());

            String outboundPrompt = initialPrompt;
            if (index > 0) {
                outboundPrompt = this.promptBuilder.buildStepPrompt(step, index + 1, strategy.getSteps().size(), lane, strategy, input);
                this.logPromptSent("STEP_PROMPT", outboundPrompt, lane, currentExecution.getId(), sessionId, step.getId(), false);
                this.logBoundarySet("STEP_PROMPT", outboundPrompt, lane, currentExecution.getId(), sessionId, step.getId(), false);
                this.codexSessionRepository.send(sessionId, outboundPrompt, lane.getSourceTerminalTty());
            }

            final LaneStepDoneResult result = this.awaitValidStepResult(lane, currentExecution.getId(), sessionId, step, outboundPrompt, correctionAttempts);
            if (result == null) {
                return;
            }

            this.logEvent("supervised.step.done.parsed", lane, currentExecution.getId(), sessionId, step.getId());
            this.persistStep(currentExecution.getId(), step, result);
            this.logEvent("supervised.step.persisted", lane, currentExecution.getId(), sessionId, step.getId());
            this.logEvent("supervised.step.completed", lane, currentExecution.getId(), sessionId, step.getId());
        }
    }

    private LaneStepDoneResult awaitValidStepResult(final ReadyToStartLane lane,
                                                    final UUID executionId,
                                                    final String sessionId,
                                                    final LaneStrategyStep step,
                                                    final String outboundPrompt,
                                                    final int correctionAttempts) {
        int correctionsLeft = correctionAttempts;
        String currentOutboundPrompt = outboundPrompt;
        boolean promptBoundarySeen = false;
        while (true) {
            final String rawOutput = this.codexSessionRepository.waitForOutput(sessionId, STEP_OUTPUT_TIMEOUT_MS);
            String output = rawOutput;
            final boolean containsResultMarker = this.containsLaneStepDone(rawOutput);
            if (rawOutput.contains(currentOutboundPrompt)) {
                promptBoundarySeen = true;
                output = this.extractResponseAfterPromptBoundary(rawOutput, currentOutboundPrompt);
                if (output.isBlank()) {
                    this.logEvent("codex.session.prompt.echo.ignored", lane, executionId, sessionId, step.getId());
                    this.logOutputClassification(lane, executionId, sessionId, step.getId(), rawOutput, output, "PROMPT_ECHO");
                    continue;
                }
                this.logEvent("codex.session.prompt.echo.ignored", lane, executionId, sessionId, step.getId());
                this.logOutputClassification(lane, executionId, sessionId, step.getId(), rawOutput, output, "PROMPT_ECHO_WITH_RESPONSE");
            } else if (!promptBoundarySeen && !containsResultMarker) {
                this.logOutputClassification(lane, executionId, sessionId, step.getId(), rawOutput, "", "NO_PROMPT_BOUNDARY_YET");
                continue;
            } else if (!promptBoundarySeen && containsResultMarker) {
                promptBoundarySeen = true;
            }
            if (output.isBlank()) {
                this.logOutputClassification(lane, executionId, sessionId, step.getId(), rawOutput, output, "EMPTY_AFTER_ECHO_STRIP");
                continue;
            }
            final boolean containsLaneStepDone = this.containsLaneStepDone(output);
            this.logEvent("codex.session.real_output.received", lane, executionId, sessionId, step.getId());
            this.logOutputClassification(lane, executionId, sessionId, step.getId(), rawOutput, output, containsLaneStepDone ? "STEP_RESULT_CANDIDATE" : "NON_RESULT_OUTPUT");
            try {
                return this.parser.parse(output, step.getId());
            } catch (final IllegalArgumentException ex) {
                if (correctionsLeft <= 0) {
                    return null;
                }
                correctionsLeft--;
                final String correctionPrompt = this.promptBuilder.buildCorrectionPrompt(step.getId());
                this.logPromptSent("CORRECTION_PROMPT", correctionPrompt, lane, executionId, sessionId, step.getId(), false);
                this.logBoundarySet("CORRECTION_PROMPT", correctionPrompt, lane, executionId, sessionId, step.getId(), false);
                this.codexSessionRepository.send(sessionId, correctionPrompt, lane.getSourceTerminalTty());
                currentOutboundPrompt = correctionPrompt;
            }
        }
    }

    private LaneExecution createExecution(final ReadyToStartLane lane, final LaneStrategy strategy, final String sessionId) {
        return this.laneExecutionRepository.saveExecution(LaneExecution.builder()
                .id(UUID.randomUUID())
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .strategyId(strategy.getAgentId())
                .strategyVersion(strategy.getVersion())
                .sessionId(sessionId)
                .currentStepId(strategy.getSteps().getFirst().getId())
                .startedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private void persistStep(final UUID executionId, final LaneStrategyStep step, final LaneStepDoneResult result) {
        this.laneExecutionRepository.saveStepExecution(LaneStepExecution.builder()
                .id(UUID.randomUUID())
                .executionId(executionId)
                .stepId(step.getId())
                .stepOrder(step.getOrder())
                .startedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .done(true)
                .resultJson(result.getRawJson())
                .evidenceJson(this.serializeEvidence(result))
                .build());
    }

    private String serializeEvidence(final LaneStepDoneResult result) {
        try {
            return this.objectMapper.writeValueAsString(result.getEvidence());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to persist step evidence json", e);
        }
    }

    private void logPromptSent(final String promptType,
                               final String prompt,
                               final ReadyToStartLane lane,
                               final UUID executionId,
                               final String sessionId,
                               final String stepId,
                               final boolean combinedInitialSend) {
        log.info(this.baseLog("supervised.prompt.sent", lane, executionId, sessionId, stepId)
                + " promptType=" + promptType
                + " chars=" + prompt.length()
                + " hash=" + this.promptHash(prompt)
                + " combinedInitialSend=" + combinedInitialSend);
    }

    private void logBoundarySet(final String promptType,
                                final String prompt,
                                final ReadyToStartLane lane,
                                final UUID executionId,
                                final String sessionId,
                                final String stepId,
                                final boolean combinedInitialSend) {
        log.info(this.baseLog("codex.session.output.boundary.set", lane, executionId, sessionId, stepId)
                + " promptType=" + promptType
                + " chars=" + prompt.length()
                + " hash=" + this.promptHash(prompt)
                + " combinedInitialSend=" + combinedInitialSend);
    }

    private void logOutputClassification(final ReadyToStartLane lane,
                                         final UUID executionId,
                                         final String sessionId,
                                         final String stepId,
                                         final String rawOutput,
                                         final String classifiedOutput,
                                         final String classification) {
        log.info(this.baseLog("codex.session.output.classified", lane, executionId, sessionId, stepId)
                + " classification=" + classification
                + " rawChars=" + rawOutput.length()
                + " rawHash=" + this.promptHash(rawOutput)
                + " chars=" + classifiedOutput.length()
                + " hash=" + this.promptHash(classifiedOutput));
    }

    private void logEvent(final String event,
                          final ReadyToStartLane lane,
                          final UUID executionId,
                          final String sessionId,
                          final String stepId) {
        log.info(this.baseLog(event, lane, executionId, sessionId, stepId));
    }

    private String baseLog(final String event,
                           final ReadyToStartLane lane,
                           final UUID executionId,
                           final String sessionId,
                           final String stepId) {
        final StringBuilder builder = new StringBuilder("event=").append(event)
                .append(" ticketId=").append(lane.getTicketId())
                .append(" laneId=").append(lane.getLaneId())
                .append(" agentId=").append(lane.getAgent().getId())
                .append(" scope=").append(lane.getScope());
        if (executionId != null) {
            builder.append(" executionId=").append(executionId);
        }
        if (sessionId != null) {
            builder.append(" sessionId=").append(sessionId);
        }
        if (stepId != null) {
            builder.append(" stepId=").append(stepId);
        }
        return builder.toString();
    }

    private boolean containsLaneStepDone(final String output) {
        return output.contains("LANE_STEP_DONE") || output.contains("<<<LANE_STEP_DONE_JSON>>>");
    }

    private String extractResponseAfterPromptBoundary(final String output, final String prompt) {
        if (output == null || output.isBlank()) {
            return null;
        }
        if (prompt == null || prompt.isBlank()) {
            return output;
        }
        final int boundaryIndex = output.lastIndexOf(prompt);
        if (boundaryIndex < 0) {
            return null;
        }
        return output.substring(boundaryIndex + prompt.length()).stripLeading();
    }

    private String promptHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (final NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
