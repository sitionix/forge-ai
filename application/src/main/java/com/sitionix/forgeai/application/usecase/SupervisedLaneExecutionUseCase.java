package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
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
    private final LaneStrategyRepository laneStrategyRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final CodexSessionRepository codexSessionRepository;
    private final LaneStepDoneResultParser parser;
    private final ObjectMapper objectMapper;

    public void execute(final ReadyToStartLane lane, final int correctionAttempts) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final String sessionId = this.codexSessionRepository.start(this.initialPrompt(lane, strategy), lane.getSourceTerminalTty());
        LaneExecution execution = this.laneExecutionRepository.saveExecution(LaneExecution.builder()
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

        try {
            for (int i = 0; i < strategy.getSteps().size(); i++) {
                final LaneStrategyStep step = strategy.getSteps().get(i);
                execution = execution.toBuilder().currentStepId(step.getId()).updatedAt(LocalDateTime.now()).build();
                this.laneExecutionRepository.updateCurrentStep(execution);

                this.codexSessionRepository.send(sessionId, this.stepPrompt(lane, step, i + 1, strategy.getSteps().size()), lane.getSourceTerminalTty());

                boolean done = false;
                for (int attempt = 0; attempt <= correctionAttempts; attempt++) {
                    final String output = this.codexSessionRepository.waitForOutput(sessionId, 120_000L);
                    try {
                        final LaneStepDoneResult result = this.parser.parse(output, step.getId());
                        this.persistStep(execution.getId(), step, result);
                        done = true;
                        break;
                    } catch (IllegalArgumentException ex) {
                        if (attempt == correctionAttempts) {
                            log.warning("Step validation exhausted for step=" + step.getId() + " lane=" + lane.getLaneId() + " reason=" + ex.getMessage());
                            return;
                        }
                        this.codexSessionRepository.send(sessionId, this.correctionPrompt(step.getId()), lane.getSourceTerminalTty());
                    }
                }
                if (!done) {
                    return;
                }
            }
        } finally {
            this.codexSessionRepository.close(sessionId);
        }
    }

    private void persistStep(final UUID executionId, final LaneStrategyStep step, final LaneStepDoneResult result) {
        try {
            this.laneExecutionRepository.saveStepExecution(LaneStepExecution.builder()
                    .id(UUID.randomUUID())
                    .executionId(executionId)
                    .stepId(step.getId())
                    .stepOrder(step.getOrder())
                    .startedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .done(true)
                    .resultJson(result.getRawJson())
                    .evidenceJson(this.objectMapper.writeValueAsString(result.getEvidence()))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to persist step evidence json", e);
        }
    }

    private String initialPrompt(final ReadyToStartLane lane, final LaneStrategy strategy) {
        return "You are in supervised lane session. TicketId=" + lane.getTicketId()
                + ", LaneId=" + lane.getLaneId()
                + ", Agent=" + lane.getAgent().getId()
                + ", Scope=" + lane.getScope()
                + ". Supervisor will send steps one by one. Wait for step prompt."
                + " Only LANE_STEP_DONE JSON is valid step completion signal. Strategy=" + strategy.getAgentId();
    }

    private String stepPrompt(final ReadyToStartLane lane, final LaneStrategyStep step, final int index, final int total) {
        return "You are executing lane step " + index + "/" + total + ".\n"
                + "TicketId: " + lane.getTicketId() + "\n"
                + "LaneId: " + lane.getLaneId() + "\n"
                + "Agent: " + lane.getAgent().getId() + "\n"
                + "Scope: " + lane.getScope() + "\n"
                + "Step id: " + step.getId() + "\n"
                + "Step title: " + step.getTitle() + "\n"
                + "Instruction refs for this step only:\n- " + String.join("\n- ", step.getInstructionRefs()) + "\n"
                + "Do not execute later steps yet.\n"
                + "Return only valid JSON:\n"
                + "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"" + step.getId() + "\",\"summary\":\"...\",\"evidence\":{}}";
    }

    private String correctionPrompt(final String stepId) {
        return "Your previous response did not match schema. Return only JSON:\n"
                + "{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"" + stepId + "\",\"summary\":\"...\",\"evidence\":{}}";
    }
}
