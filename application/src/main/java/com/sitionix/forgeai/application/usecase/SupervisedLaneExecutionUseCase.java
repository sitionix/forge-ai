package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneCompletionDispatcher;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
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

    private final LaneStrategyRepository laneStrategyRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final CodexSessionRepository codexSessionRepository;
    private final LaneStepPromptBuilder promptBuilder;
    private final LaneStepDoneResultParser resultParser;
    private final LaneCompletionDispatcher laneCompletionDispatcher;
    private final SupervisedExecutionProperties supervisedExecutionProperties;
    private final ObjectMapper objectMapper;

    public void execute(final ReadyToStartLane lane,
                        final AgentExecutionInput<AgentTicketPayload> input,
                        final int correctionAttempts) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final UUID executionId = UUID.randomUUID();
        final CodexSession session = this.codexSessionRepository.openSession(CodexSessionStartCommand.builder()
                .workspaceRoot(java.nio.file.Path.of("").toAbsolutePath().normalize().toString())
                .sourceTerminalTty(lane.getSourceTerminalTty())
                .ticketKey(lane.getTicketKey())
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .build());
        final LaneExecution execution = this.createExecution(lane, strategy, session.id(), executionId);
        boolean completed = false;

        this.logEvent("supervised.execution.started", lane, execution.getId(), session.id(), null);
        this.logEvent("codex.session.started", lane, execution.getId(), session.id(), null);

        try {
            completed = this.runSteps(lane, input, strategy, execution, session.id(), correctionAttempts);
            if (completed) {
                this.logEvent("supervised.execution.steps.completed", lane, execution.getId(), session.id(), null);
            } else {
                this.logEvent("codex.session.left_open", lane, execution.getId(), session.id(), null);
            }
        } finally {
            if (completed) {
                this.codexSessionRepository.closeSession(session.id());
                this.logEvent("codex.session.closed", lane, execution.getId(), session.id(), null);
            }
        }
    }

    private boolean runSteps(final ReadyToStartLane lane,
                             final AgentExecutionInput<AgentTicketPayload> input,
                             final LaneStrategy strategy,
                             final LaneExecution execution,
                             final String sessionId,
                             final int correctionAttempts) {
        LaneExecution currentExecution = execution;
        for (int index = 0; index < strategy.getSteps().size(); index++) {
            final LaneStrategyStep step = strategy.getSteps().get(index);
            final boolean finalStep = index == strategy.getSteps().size() - 1;
            currentExecution = currentExecution.toBuilder()
                    .currentStepId(step.getId())
                    .updatedAt(LocalDateTime.now())
                    .build();
            this.laneExecutionRepository.updateCurrentStep(currentExecution);
            this.logEvent("supervised.step.started", lane, currentExecution.getId(), sessionId, step.getId());

            final String prompt = index == 0
                    ? this.promptBuilder.buildStartPrompt(lane, strategy, input) + "\n\n" + this.promptBuilder.buildStepPrompt(lane, strategy, step, input, index + 1, strategy.getSteps().size())
                    : this.promptBuilder.buildStepPrompt(lane, strategy, step, input, index + 1, strategy.getSteps().size());

            this.logPromptSize("STEP_PROMPT", prompt, lane, currentExecution.getId(), sessionId, step.getId(), index == 0);

            final LaneStepDoneResult result;
            try {
                result = this.awaitValidStepResult(lane, currentExecution.getId(), sessionId, step, prompt, correctionAttempts, finalStep);
            } catch (final IllegalStateException ex) {
                if (this.isTurnTimeout(ex)) {
                    this.logEvent("supervised.step.result.timeout", lane, currentExecution.getId(), sessionId, step.getId());
                    return false;
                }
                throw ex;
            }
            if (result == null) {
                return false;
            }

            this.logEvent("supervised.step.result.validated", lane, currentExecution.getId(), sessionId, step.getId());
            this.persistStep(currentExecution.getId(), step, result);
            this.logEvent("supervised.step.persisted", lane, currentExecution.getId(), sessionId, step.getId());
            if (finalStep) {
                this.laneCompletionDispatcher.completeLane(lane, result.getEvidence());
                this.logEvent("supervised.lane.completed", lane, currentExecution.getId(), sessionId, step.getId());
            }
        }
        return true;
    }

    private LaneStepDoneResult awaitValidStepResult(final ReadyToStartLane lane,
                                                    final UUID executionId,
                                                    final String sessionId,
                                                    final LaneStrategyStep step,
                                                    final String prompt,
                                                    final int correctionAttempts,
                                                    final boolean finalStep) {
        int correctionsLeft = correctionAttempts;
        String response = this.submitTurn(lane, executionId, sessionId, step.getId(), prompt, "STEP_PROMPT");
        while (true) {
            try {
                final LaneStepDoneResult result = this.resultParser.parse(response, step.getId());
                if (finalStep) {
                    this.laneCompletionDispatcher.validateFinalCompletionPayload(lane, result.getEvidence());
                }
                return result;
            } catch (final IllegalArgumentException ex) {
                if (correctionsLeft <= 0) {
                    return null;
                }
                correctionsLeft--;
                final String correctionPrompt = this.promptBuilder.buildCorrectionPrompt(lane, step, ex.getMessage(), finalStep);
                this.logPromptSize("CORRECTION_PROMPT", correctionPrompt, lane, executionId, sessionId, step.getId(), false);
                response = this.submitTurn(lane, executionId, sessionId, step.getId(), correctionPrompt, "CORRECTION_PROMPT");
            }
        }
    }

    private String submitTurn(final ReadyToStartLane lane,
                              final UUID executionId,
                              final String sessionId,
                              final String stepId,
                              final String prompt,
                              final String promptType) {
        this.logEvent("supervised.step.turn.sent", lane, executionId, sessionId, stepId);
        final CodexTurnResponse response = this.codexSessionRepository.submitTurn(sessionId, CodexTurnCommand.builder()
                .prompt(prompt)
                .timeout(this.supervisedExecutionProperties.getTurnTimeout())
                .promptType(promptType)
                .stepId(stepId)
                .build());
        this.logEvent("supervised.step.turn.response.received", lane, executionId, sessionId, stepId);
        final String assistantResponse = response == null ? null : response.assistantResponse();
        log.info(this.baseLog("supervised.step.turn.response.received", lane, executionId, sessionId, stepId)
                + " promptType=" + promptType
                + " responseChars=" + (assistantResponse == null ? 0 : assistantResponse.length())
                + " responseHash=" + this.promptHash(assistantResponse == null ? "" : assistantResponse)
                + " turnId=" + (response == null ? "" : response.turnId()));
        return assistantResponse;
    }

    private LaneExecution createExecution(final ReadyToStartLane lane, final LaneStrategy strategy, final String sessionId, final UUID executionId) {
        return this.laneExecutionRepository.saveExecution(LaneExecution.builder()
                .id(executionId)
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

    private void logPromptSize(final String promptType,
                               final String prompt,
                               final ReadyToStartLane lane,
                               final UUID executionId,
                               final String sessionId,
                               final String stepId,
                               final boolean combinedInitialSend) {
        final Integer warningThreshold = this.supervisedExecutionProperties.getOutgoingPromptWarningChars();
        final Integer failThreshold = this.supervisedExecutionProperties.getOutgoingPromptFailChars();
        final boolean warningTriggered = warningThreshold != null && prompt.length() > warningThreshold;
        final boolean failTriggered = failThreshold != null && prompt.length() > failThreshold;
        final String action = failTriggered ? "fail" : warningTriggered ? "warning_only" : "sent";
        final java.util.logging.Level level = failTriggered ? java.util.logging.Level.SEVERE
                : warningTriggered ? java.util.logging.Level.WARNING
                : java.util.logging.Level.INFO;
        log.log(level, this.baseLog("supervised.prompt.size", lane, executionId, sessionId, stepId)
                + " promptType=" + promptType
                + " chars=" + prompt.length()
                + " hash=" + this.promptHash(prompt)
                + " action=" + action
                + (warningThreshold == null ? "" : " warningThreshold=" + warningThreshold)
                + (failThreshold == null ? "" : " failThreshold=" + failThreshold)
                + " combinedInitialSend=" + combinedInitialSend);
        if (failTriggered) {
            throw new IllegalStateException("Outgoing supervised prompt exceeds configured fail threshold: promptType=" + promptType
                    + ", chars=" + prompt.length()
                    + ", failThreshold=" + failThreshold);
        }
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

    private String promptHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (final NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private boolean isTurnTimeout(final IllegalStateException ex) {
        return ex.getMessage() != null && ex.getMessage().startsWith("Timed out");
    }
}
