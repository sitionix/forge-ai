package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneCompletionDispatcher;
import com.sitionix.forgeai.application.laneexecution.LaneExecutionProgressService;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEventType;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnInterruptedException;
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
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Level;
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
    private final LaneExecutionProgressService laneExecutionProgressService;
    private final SupervisedExecutionProperties supervisedExecutionProperties;
    private final ObjectMapper objectMapper;
    private final ManageTicketOperatorRuns manageTicketOperatorRuns;

    public void execute(final ReadyToStartLane lane,
                        final AgentExecutionInput<AgentTicketPayload> input,
                        final int correctionAttempts) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final UUID executionId = UUID.randomUUID();
        final LaneExecution execution = this.laneExecutionProgressService.createStartingExecution(lane, strategy, executionId);
        final CodexSession session = this.codexSessionRepository.openSession(CodexSessionStartCommand.builder()
                .executionId(executionId)
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .workspaceRoot(Path.of("").toAbsolutePath().normalize().toString())
                .sourceTerminalTty(lane.getSourceTerminalTty())
                .ticketKey(lane.getTicketKey())
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .build());
        this.laneExecutionProgressService.markSessionStarted(executionId, session);
        boolean completed = false;
        boolean sessionClosed = false;

        this.logEvent("supervised.execution.started", lane, executionId, session.id(), null);
        this.logEvent("codex.session.started", lane, executionId, session.id(), null);

        try {
            completed = this.runSteps(lane, input, strategy, execution, session.id(), correctionAttempts);
            if (completed) {
                this.laneExecutionProgressService.markCompleted(executionId);
                this.logEvent("supervised.execution.steps.completed", lane, executionId, session.id(), null);
            } else {
                this.logEvent("codex.session.left_open", lane, executionId, session.id(), null);
            }
        } catch (final CodexTurnInterruptedException ex) {
            this.laneExecutionProgressService.markInterrupted(executionId, ex.getMessage());
            this.laneExecutionProgressService.publishLaneInterrupted(lane, executionId, ex.getMessage());
            this.logEvent("supervised.execution.interrupted", lane, executionId, session.id(), null);
        } catch (final TicketExecutionCancelledException ex) {
            this.laneExecutionProgressService.markCancelled(executionId, ex.getMessage());
            this.laneExecutionProgressService.publishLaneInterrupted(lane, executionId, ex.getMessage());
            this.codexSessionRepository.closeSession(session.id());
            sessionClosed = true;
            this.logEvent("supervised.execution.cancelled", lane, executionId, session.id(), null);
        } catch (final RuntimeException ex) {
            this.laneExecutionProgressService.markFailed(executionId, ex.getMessage());
            final LaneStrategyStep failedStep = strategy.getSteps().stream()
                    .filter(step -> step.getId().equals(this.laneExecutionProgressService.getExecution(executionId).getCurrentStepId()))
                    .findFirst()
                    .orElse(strategy.getSteps().getFirst());
            this.laneExecutionProgressService.publishLaneFailed(
                    lane,
                    executionId,
                    failedStep,
                    strategy.getSteps().size(),
                    ex.getMessage()
            );
            throw ex;
        } finally {
            if (completed && !sessionClosed) {
                this.codexSessionRepository.closeSession(session.id());
                this.logEvent("codex.session.closed", lane, executionId, session.id(), null);
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
            this.laneExecutionProgressService.markStepStarted(currentExecution.getId(), step);
            this.laneExecutionProgressService.publishStepStarted(lane, currentExecution.getId(), step, strategy.getSteps().size());
            this.logEvent("supervised.step.started", lane, currentExecution.getId(), sessionId, step.getId());

            final String prompt = index == 0
                    ? this.promptBuilder.buildStartPrompt(lane, strategy, input) + "\n\n"
                    + this.promptBuilder.buildStepPrompt(lane, strategy, step, input, index + 1, strategy.getSteps().size())
                    : this.promptBuilder.buildStepPrompt(lane, strategy, step, input, index + 1, strategy.getSteps().size());

            this.logPromptSize("STEP_PROMPT", prompt, lane, currentExecution.getId(), sessionId, step.getId(), index == 0);
            this.ensureTicketNotCancelled(lane, currentExecution.getId(), sessionId, step.getId(), "before_submit");

            final LaneStepDoneResult result;
            try {
                result = this.awaitValidStepResult(
                        lane,
                        currentExecution.getId(),
                        sessionId,
                        step,
                        prompt,
                        correctionAttempts,
                        finalStep,
                        strategy.getSteps().size()
                );
            } catch (final IllegalStateException ex) {
                if (this.isTurnTimeout(ex)) {
                    this.laneExecutionProgressService.markFailed(currentExecution.getId(), ex.getMessage());
                    this.laneExecutionProgressService.publishLaneFailed(lane, currentExecution.getId(), step, strategy.getSteps().size(), ex.getMessage());
                    this.logEvent("supervised.step.result.timeout", lane, currentExecution.getId(), sessionId, step.getId());
                    return false;
                }
                throw ex;
            }
            if (result == null) {
                return false;
            }

            this.ensureTicketNotCancelled(lane, currentExecution.getId(), sessionId, step.getId(), "before_validation");
            this.laneExecutionProgressService.markValidatingResponse(currentExecution.getId());
            this.logEvent("supervised.step.result.validated", lane, currentExecution.getId(), sessionId, step.getId());
            this.laneExecutionProgressService.publishStepValidationPassed(lane, currentExecution.getId(), step, strategy.getSteps().size());

            this.ensureTicketNotCancelled(lane, currentExecution.getId(), sessionId, step.getId(), "before_persist");
            this.laneExecutionProgressService.markPersistingStep(currentExecution.getId());
            this.persistStep(currentExecution.getId(), step, result);
            this.logEvent("supervised.step.persisted", lane, currentExecution.getId(), sessionId, step.getId());
            this.laneExecutionProgressService.publishStepPersisted(lane, currentExecution.getId(), step, strategy.getSteps().size());

            if (finalStep) {
                this.ensureTicketNotCancelled(lane, currentExecution.getId(), sessionId, step.getId(), "before_completion");
                this.laneExecutionProgressService.markCompletingLane(currentExecution.getId());
                this.laneCompletionDispatcher.completeLane(lane, result.getEvidence());
                this.logEvent("supervised.lane.completed", lane, currentExecution.getId(), sessionId, step.getId());
                this.laneExecutionProgressService.publishLaneCompleted(lane, currentExecution.getId(), step, strategy.getSteps().size());
            } else {
                this.ensureTicketNotCancelled(lane, currentExecution.getId(), sessionId, step.getId(), "before_next_step");
                this.laneExecutionProgressService.publishNextStep(lane, currentExecution.getId(), step, strategy.getSteps().get(index + 1));
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
                                                    final boolean finalStep,
                                                    final int totalSteps) {
        int correctionsLeft = correctionAttempts;
        String response = this.submitTurn(lane, executionId, sessionId, step.getId(), prompt, "STEP_PROMPT");
        this.laneExecutionProgressService.publishStepResponseReceived(lane, executionId, step, totalSteps);
        while (true) {
            try {
                this.ensureTicketNotCancelled(lane, executionId, sessionId, step.getId(), "after_response");
                final LaneStepDoneResult result = this.resultParser.parse(response, step.getId());
                if (finalStep) {
                    this.laneCompletionDispatcher.validateFinalCompletionPayload(lane, result.getEvidence());
                }
                return result;
            } catch (final IllegalArgumentException ex) {
                this.laneExecutionProgressService.publishStepValidationFailed(lane, executionId, step, totalSteps, ex.getMessage());
                if (correctionsLeft <= 0) {
                    return null;
                }
                correctionsLeft--;
                this.laneExecutionProgressService.markCorrectionRunning(executionId);
                this.laneExecutionProgressService.publishCorrectionStarted(lane, executionId, step, totalSteps, ex.getMessage());
                final String correctionPrompt = this.promptBuilder.buildCorrectionPrompt(lane, step, ex.getMessage(), finalStep);
                this.logPromptSize("CORRECTION_PROMPT", correctionPrompt, lane, executionId, sessionId, step.getId(), false);
                response = this.submitTurn(lane, executionId, sessionId, step.getId(), correctionPrompt, "CORRECTION_PROMPT");
                this.laneExecutionProgressService.publishStepResponseReceived(lane, executionId, step, totalSteps);
            }
        }
    }

    private String submitTurn(final ReadyToStartLane lane,
                              final UUID executionId,
                              final String sessionId,
                              final String stepId,
                              final String prompt,
                              final String promptType) {
        this.ensureTicketNotCancelled(lane, executionId, sessionId, stepId, "before_turn_submit");
        this.laneExecutionProgressService.markWaitingForCodex(executionId);
        this.logEvent("supervised.step.turn.sent", lane, executionId, sessionId, stepId);
        final CodexTurnResponse response = this.codexSessionRepository.submitTurn(sessionId, CodexTurnCommand.builder()
                .prompt(prompt)
                .timeout(this.supervisedExecutionProperties.getTurnTimeout())
                .promptType(promptType)
                .stepId(stepId)
                .stepOrder(this.laneExecutionProgressService.getExecution(executionId).getCurrentStepOrder())
                .stepTitle(this.laneExecutionProgressService.getExecution(executionId).getCurrentStepTitle())
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

    private void ensureTicketNotCancelled(final ReadyToStartLane lane,
                                          final UUID executionId,
                                          final String sessionId,
                                          final String stepId,
                                          final String phase) {
        if (!this.manageTicketOperatorRuns.isExecutionBlocked(lane.getTicketId())) {
            return;
        }
        this.laneExecutionProgressService.onEvent(CodexProgressEvent.builder()
                .executionId(executionId)
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .sessionId(sessionId)
                .stepId(stepId)
                .eventType(CodexProgressEventType.TURN_INTERRUPTED)
                .text("Ticket execution cancelled during " + phase)
                .occurredAt(Instant.now())
                .build());
        throw new TicketExecutionCancelledException("Ticket operator run cancelled: ticketId="
                + lane.getTicketId() + ", stepId=" + stepId + ", phase=" + phase);
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
        final Level level = failTriggered ? Level.SEVERE
                : warningTriggered ? Level.WARNING
                : Level.INFO;
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
