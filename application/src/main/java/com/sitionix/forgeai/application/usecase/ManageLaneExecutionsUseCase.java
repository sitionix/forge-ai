package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.laneexecution.LaneExecutionProgressService;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.usecase.ManageLaneExecutions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageLaneExecutionsUseCase implements ManageLaneExecutions {

    private final LaneExecutionProgressService laneExecutionProgressService;
    private final CodexSessionRepository codexSessionRepository;
    private final SupervisedExecutionProperties supervisedExecutionProperties;

    @Override
    public List<LaneExecution> findActiveExecutions() {
        return this.laneExecutionProgressService.findActiveExecutions();
    }

    @Override
    public LaneExecution getExecution(final UUID executionId) {
        return this.laneExecutionProgressService.getExecution(executionId);
    }

    @Override
    public LaneExecution interrupt(final UUID executionId) {
        final LaneExecution execution = this.laneExecutionProgressService.markCancelRequested(executionId);
        final String sessionId = execution.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return this.laneExecutionProgressService.markCancelled(executionId, "Execution cancelled before Codex session was established");
        }
        try {
            if (execution.getActiveTurnId() != null && !execution.getActiveTurnId().isBlank()) {
                this.codexSessionRepository.interruptTurn(
                        sessionId,
                        execution.getActiveTurnId(),
                        this.supervisedExecutionProperties.getTurnTimeout()
                );
                this.codexSessionRepository.closeSession(sessionId);
                return this.laneExecutionProgressService.markInterrupted(
                        executionId,
                        "Execution interrupted via operator request for turnId=" + execution.getActiveTurnId()
                );
            }
            this.codexSessionRepository.closeSession(sessionId);
            return this.laneExecutionProgressService.markCancelled(executionId, "Execution cancelled without active turn");
        } catch (final RuntimeException ex) {
            this.codexSessionRepository.closeSession(sessionId);
            return this.laneExecutionProgressService.markInterrupted(
                    executionId,
                    "Interrupt cleanup completed after transport failure: " + ex.getMessage()
            );
        }
    }
}
