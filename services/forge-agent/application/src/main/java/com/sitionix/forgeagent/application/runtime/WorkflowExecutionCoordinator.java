package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionCoordinator {

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunGraphRepository graphRepository;
    private final NodeRunRepository nodeRunRepository;
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
                workflowRun.executionEdges(),
                workflowRun.runtimeGraph(),
                workflowRun.result(),
                workflowRun.resultSourceNodeRunId(),
                workflowRun.createdAt(),
                workflowRun.startedAt(),
                workflowRun.finishedAt() == null ? Instant.now(this.clock) : workflowRun.finishedAt(),
                workflowRun.repositoryIds()
        );
    }

    private WorkflowRun withSuccessfulResult(final WorkflowRun workflowRun) {
        final WorkflowRunGraph graph = workflowRun.runtimeGraph() == null
                ? this.graphRepository.findByWorkflowRunId(workflowRun.id())
                : workflowRun.runtimeGraph();
        if (graph == null || graph.taskOutputPortId() == null) {
            return this.withStatus(workflowRun, WorkflowRunStatus.SUCCEEDED);
        }
        final List<NodeRun> emissions = this.nodeRunRepository.findByWorkflowRunId(workflowRun.id()).stream()
                .filter(nodeRun -> nodeRun.status() == NodeRunStatus.SUCCEEDED)
                .filter(nodeRun -> nodeRun.routingCompletedAt() != null)
                .filter(nodeRun -> graph.taskOutputPortId().equals(nodeRun.selectedOutputPortId()))
                .filter(nodeRun -> nodeRun.output() != null)
                .sorted(Comparator.comparing(NodeRun::createdAt).thenComparing(NodeRun::id))
                .toList();
        if (emissions.isEmpty()) {
            return this.withStatus(workflowRun, WorkflowRunStatus.FAILED);
        }
        final NodeRun selected = emissions.get(emissions.size() - 1);
        return new WorkflowRun(
                workflowRun.id(),
                workflowRun.projectId(),
                workflowRun.sourceWorkflowId(),
                workflowRun.taskId(),
                workflowRun.workflowName(),
                workflowRun.input(),
                WorkflowRunStatus.SUCCEEDED,
                workflowRun.nodeRuns(),
                workflowRun.connectionResolutions(),
                workflowRun.executionEdges(),
                graph,
                selected.output(),
                selected.id(),
                workflowRun.createdAt(),
                workflowRun.startedAt(),
                workflowRun.finishedAt() == null ? Instant.now(this.clock) : workflowRun.finishedAt(),
                workflowRun.repositoryIds()
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
                    WorkflowExecutionCoordinator.this.withSuccessfulResult(this.workflowRun)
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
