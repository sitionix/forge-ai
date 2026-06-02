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
import java.time.LocalDateTime;
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
        final String sessionId = this.codexSessionRepository.start(
                this.promptBuilder.startPrompt(lane, input, strategy.getSteps().size()),
                lane.getSourceTerminalTty()
        );
        final LaneExecution execution = this.createExecution(lane, strategy, sessionId);

        this.logEvent("supervised.execution.started", lane, execution.getId(), sessionId, null);
        this.logEvent("codex.session.started", lane, execution.getId(), sessionId, null);

        try {
            this.runSteps(lane, input, strategy, execution, sessionId, correctionAttempts);
            this.logEvent("supervised.execution.steps.completed", lane, execution.getId(), sessionId, null);
        } finally {
            this.codexSessionRepository.close(sessionId);
        }
    }

    private void runSteps(final ReadyToStartLane lane,
                          final AgentExecutionInput<AgentTicketPayload> input,
                          final LaneStrategy strategy,
                          final LaneExecution execution,
                          final String sessionId,
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

            this.codexSessionRepository.send(
                    sessionId,
                    this.promptBuilder.stepPrompt(lane, step, index + 1, strategy.getSteps().size(), input),
                    lane.getSourceTerminalTty()
            );
            this.logEvent("codex.session.message.sent", lane, currentExecution.getId(), sessionId, step.getId());

            final LaneStepDoneResult result = this.awaitValidStepResult(lane, currentExecution.getId(), sessionId, step, correctionAttempts);
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
                                                    final int correctionAttempts) {
        int attemptsLeft = correctionAttempts + 1;
        while (attemptsLeft > 0) {
            final String output = this.codexSessionRepository.waitForOutput(sessionId, STEP_OUTPUT_TIMEOUT_MS);
            this.logEvent("codex.session.output.received", lane, executionId, sessionId, step.getId());
            try {
                return this.parser.parse(output, step.getId());
            } catch (final IllegalArgumentException ex) {
                attemptsLeft--;
                if (attemptsLeft == 0) {
                    return null;
                }
                this.codexSessionRepository.send(
                        sessionId,
                        this.promptBuilder.correctionPrompt(step.getId()),
                        lane.getSourceTerminalTty()
                );
                this.logEvent("supervised.correction.sent", lane, executionId, sessionId, step.getId());
            }
        }
        return null;
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

    private void logEvent(final String event,
                          final ReadyToStartLane lane,
                          final UUID executionId,
                          final String sessionId,
                          final String stepId) {
        final StringBuilder builder = new StringBuilder(event)
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
        log.info(builder.toString());
    }
}
