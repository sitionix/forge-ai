package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionCoordinator {

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowCompletionPolicy completionPolicy;
    private final Clock clock;

    public WorkflowCompletionDecisionHandler completionDecisionHandler(final WorkflowRun workflowRun) {
        return new CompletionDecisionHandler(workflowRun);
    }

    public void reconcile(final WorkflowRun workflowRun) {
        this.completionPolicy.evaluate(workflowRun).apply(this.completionDecisionHandler(workflowRun));
    }

    private WorkflowRun withStatus(final WorkflowRun workflowRun, final WorkflowRunStatus status) {
        return new WorkflowRun(
                workflowRun.id(),
                workflowRun.projectId(),
                workflowRun.sourceWorkflowId(),
                workflowRun.taskId(),
                workflowRun.workflowName(),
                workflowRun.input(),
                status,
                workflowRun.nodeRuns(),
                workflowRun.connectionResolutions(),
                workflowRun.createdAt(),
                workflowRun.startedAt(),
                workflowRun.finishedAt() == null ? Instant.now(this.clock) : workflowRun.finishedAt()
        );
    }

    private final class CompletionDecisionHandler implements WorkflowCompletionDecisionHandler {
        private final WorkflowRun workflowRun;

        private CompletionDecisionHandler(final WorkflowRun workflowRun) {
            this.workflowRun = workflowRun;
        }

        @Override
        public void handle(final RunningWorkflowDecision decision) {
        }

        @Override
        public void handle(final SuccessfulWorkflowDecision decision) {
            WorkflowExecutionCoordinator.this.workflowRunRepository.saveLifecycle(
                    WorkflowExecutionCoordinator.this.withStatus(this.workflowRun, WorkflowRunStatus.SUCCEEDED)
            );
        }

        @Override
        public void handle(final FailedWorkflowDecision decision) {
            WorkflowExecutionCoordinator.this.workflowRunRepository.saveLifecycle(
                    WorkflowExecutionCoordinator.this.withStatus(this.workflowRun, WorkflowRunStatus.FAILED)
            );
        }
    }
}
