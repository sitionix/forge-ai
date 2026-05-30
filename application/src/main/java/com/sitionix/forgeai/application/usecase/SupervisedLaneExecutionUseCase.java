package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupervisedLaneExecutionUseCase {

    private static final Logger log = Logger.getLogger(SupervisedLaneExecutionUseCase.class.getName());
    private final LaneStrategyRepository laneStrategyRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final CodexSessionRepository codexSessionRepository;
    private final LaneStepDoneResultParser parser;
    private final LaneStepPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private static final long STEP_OUTPUT_TIMEOUT_MS = 120_000L;

    public void execute(final ReadyToStartLane lane,
                        final AgentExecutionInput<AgentTicketPayload> input,
                        final int correctionAttempts) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final LaneStrategyStep firstStep = strategy.getSteps().getFirst();
        final String sessionId = this.codexSessionRepository.start(
                this.promptBuilder.startPrompt(lane, strategy)
                        + "\n\n"
                        + this.promptBuilder.stepPrompt(firstStep, 1, strategy.getSteps().size(), input.getTasks()),
                lane.getSourceTerminalTty()
        );
        LaneExecution execution = this.createExecution(lane, strategy, sessionId);

        try {
            execution = this.runSteps(lane, input, strategy, sessionId, execution, correctionAttempts);
        } finally {
            this.codexSessionRepository.close(sessionId);
        }
    }

    private LaneExecution runSteps(final ReadyToStartLane lane,
                                   final AgentExecutionInput<AgentTicketPayload> input,
                                   final LaneStrategy strategy,
                                   final String sessionId,
                                   final LaneExecution initialExecution,
                                   final int correctionAttempts) {
        final AtomicReference<LaneExecution> executionRef = new AtomicReference<>(initialExecution);
        try {
            IntStream.range(0, strategy.getSteps().size())
                    .forEach(index -> executionRef.set(this.executeStep(
                            lane,
                            input,
                            strategy,
                            sessionId,
                            executionRef.get(),
                            index,
                            correctionAttempts
                    )));
        } catch (StopSupervisedExecutionException ignored) {
            return executionRef.get();
        }
        return executionRef.get();
    }

    private LaneExecution executeStep(final ReadyToStartLane lane,
                                      final AgentExecutionInput<AgentTicketPayload> input,
                                      final LaneStrategy strategy,
                                      final String sessionId,
                                      final LaneExecution execution,
                                      final int index,
                                      final int correctionAttempts) {
        final LaneStrategyStep step = strategy.getSteps().get(index);
        final LaneExecution updatedExecution = this.updateCurrentStep(execution, step.getId());
        if (index > 0) {
            this.sendStepPrompt(lane, sessionId, input, step, index + 1, strategy.getSteps().size());
        }
        final LaneStepDoneResult doneResult = this.awaitStepDoneWithCorrections(lane, sessionId, step, correctionAttempts);
        if (doneResult == null) {
            throw new StopSupervisedExecutionException();
        }
        this.persistStep(updatedExecution.getId(), step, doneResult);
        return updatedExecution;
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

    private LaneExecution updateCurrentStep(final LaneExecution execution, final String stepId) {
        final LaneExecution updated = execution.toBuilder().currentStepId(stepId).updatedAt(LocalDateTime.now()).build();
        this.laneExecutionRepository.updateCurrentStep(updated);
        return updated;
    }

    private void sendStepPrompt(final ReadyToStartLane lane,
                                final String sessionId,
                                final AgentExecutionInput<AgentTicketPayload> input,
                                final LaneStrategyStep step,
                                final int stepIndex,
                                final int totalSteps) {
        this.codexSessionRepository.send(
                sessionId,
                this.promptBuilder.stepPrompt(step, stepIndex, totalSteps, input.getTasks()),
                lane.getSourceTerminalTty()
        );
    }

    private LaneStepDoneResult awaitStepDoneWithCorrections(final ReadyToStartLane lane,
                                                            final String sessionId,
                                                            final LaneStrategyStep step,
                                                            final int correctionAttempts) {
        int attemptsLeft = correctionAttempts + 1;
        while (attemptsLeft > 0) {
            final String output = this.codexSessionRepository.waitForOutput(sessionId, STEP_OUTPUT_TIMEOUT_MS);
            try {
                return this.parser.parse(output, step.getId());
            } catch (IllegalArgumentException ex) {
                attemptsLeft--;
                if (attemptsLeft == 0) {
                    log.warning("Step validation exhausted for step=" + step.getId()
                            + " lane=" + lane.getLaneId()
                            + " reason=" + ex.getMessage());
                    return null;
                }
                this.codexSessionRepository.send(
                        sessionId,
                        this.promptBuilder.correctionPrompt(step.getId()),
                        lane.getSourceTerminalTty()
                );
            }
        }
        return null;
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to persist step evidence json", e);
        }
    }

    private static final class StopSupervisedExecutionException extends RuntimeException {
    }
}
