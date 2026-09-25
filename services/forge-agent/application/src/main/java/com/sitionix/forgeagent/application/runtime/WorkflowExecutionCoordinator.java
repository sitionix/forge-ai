package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowExecutionCoordinator {

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunGraphRepository graphRepository;
    private final NodeRunRepository nodeRunRepository;
    private final WorkflowCompletionPolicy completionPolicy;
    private final Clock clock;
    private final AgentExecutionSessionRepository sessionRepository;
    private final McpGatewayService gateway;

    @Autowired
    public WorkflowExecutionCoordinator(final WorkflowRunRepository workflowRunRepository,
                                        final WorkflowRunGraphRepository graphRepository,
                                        final NodeRunRepository nodeRunRepository,
                                        final WorkflowCompletionPolicy completionPolicy,
                                        final Clock clock,
                                        final AgentExecutionSessionRepository sessionRepository,
                                        final ObjectProvider<McpGatewayService> gateway) {
        this.workflowRunRepository = workflowRunRepository;
        this.graphRepository = graphRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.completionPolicy = completionPolicy;
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.gateway = gateway.getIfAvailable();
    }

    public WorkflowExecutionCoordinator(final WorkflowRunRepository workflowRunRepository,
                                        final WorkflowRunGraphRepository graphRepository,
                                        final NodeRunRepository nodeRunRepository,
                                        final WorkflowCompletionPolicy completionPolicy,
                                        final Clock clock,
                                        final AgentExecutionSessionRepository sessionRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.graphRepository = graphRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.completionPolicy = completionPolicy;
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.gateway = null;
    }

    WorkflowExecutionCoordinator(final WorkflowRunRepository workflowRunRepository,
                                 final WorkflowRunGraphRepository graphRepository,
                                 final NodeRunRepository nodeRunRepository,
                                 final WorkflowCompletionPolicy completionPolicy,
                                 final Clock clock) {
        this(workflowRunRepository, graphRepository, nodeRunRepository, completionPolicy, clock, null);
    }

    public WorkflowCompletionDecisionHandler completionDecisionHandler(final WorkflowRun workflowRun) {
        return new CompletionDecisionHandler(workflowRun);
    }

    public void reconcile(final WorkflowRun workflowRun) {
        this.completionPolicy.evaluate(workflowRun).apply(this.completionDecisionHandler(workflowRun));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(final UUID workflowRunId) {
        this.workflowRunRepository.findByIdForUpdate(workflowRunId)
                .filter(workflowRun -> workflowRun.finishedAt() == null && !this.isTerminal(workflowRun.status()))
                .ifPresent(this::reconcile);
    }

    private boolean isTerminal(final WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCEEDED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
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

    public boolean cancelActiveNodeRuns(final WorkflowRun workflowRun) {
        final Instant now = Instant.now(this.clock);
        return this.nodeRunRepository.findByWorkflowRunId(workflowRun.id()).stream()
                .filter(nodeRun -> nodeRun.status().active())
                .map(nodeRun -> {
                    if (nodeRun.contextTrackingVersion() != null) {
                        var allocation = this.sessionRepository.findByNodeRunId(nodeRun.id());
                        boolean cancelled = this.sessionRepository.cancel(nodeRun.id());
                        if (cancelled && gateway != null) allocation.ifPresent(value -> gateway.revokeExecution(value.turn().id()));
                        return cancelled;
                    } else {
                        this.nodeRunRepository.save(this.withCancelled(nodeRun, now));
                        return true;
                    }
                })
                .reduce(true, (allCancelled, cancelled) -> allCancelled && cancelled);
    }

    private NodeRun withCancelled(final NodeRun nodeRun, final Instant now) {
        return new NodeRun(
                nodeRun.id(), nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.sourceAgentId(),
                nodeRun.agentName(), nodeRun.agentInstructions(), nodeRun.agentOutputSchema(), nodeRun.inputMode(),
                nodeRun.position(), nodeRun.executionFrameId(), nodeRun.enteredViaInputPortId(), nodeRun.activationFrameId(),
                nodeRun.selectedOutputPortId(), nodeRun.routingCompletedAt(), NodeRunStatus.CANCELLED, nodeRun.output(),
                nodeRun.failure(), nodeRun.executionModel(), nodeRun.createdAt(), nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt(), nodeRun.repositoryId(),
                nodeRun.contextMode(), nodeRun.contextTrackingVersion(), nodeRun.retryOfNodeRunId(), nodeRun.contextGroupKey(), nodeRun.contextIterationId(), nodeRun.nodeType()
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
            WorkflowExecutionCoordinator.this.cancelActiveNodeRuns(this.workflowRun);
            WorkflowExecutionCoordinator.this.workflowRunRepository.saveLifecycle(
                    WorkflowExecutionCoordinator.this.withStatus(this.workflowRun, WorkflowRunStatus.FAILED)
            );
        }
    }
}
