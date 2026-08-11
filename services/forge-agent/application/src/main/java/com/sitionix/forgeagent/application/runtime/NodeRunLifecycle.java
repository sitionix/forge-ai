package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NodeRunLifecycle {

    public static final String SOURCE_AGENT_NOT_FOUND = "SOURCE_AGENT_NOT_FOUND";
    public static final String AGENT_MODEL_NOT_CONFIGURED = "AGENT_MODEL_NOT_CONFIGURED";
    public static final String INVALID_NODE_RUN_DEPENDENCY = "INVALID_NODE_RUN_DEPENDENCY";
    public static final String LIFECYCLE_CONFLICT = "NODE_RUN_LIFECYCLE_CONFLICT";

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final Clock clock;

    @Transactional
    public Optional<NodeExecutionClaim> tryStart(final UUID nodeRunId) {
        final Optional<UUID> workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) {
            return Optional.empty();
        }

        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId.get())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        if (this.isTerminal(workflowRun.status())) {
            return Optional.empty();
        }
        final Optional<NodeRun> locked = this.nodeRunRepository.findByIdForUpdate(nodeRunId);
        if (locked.isEmpty() || locked.get().status() != NodeRunStatus.PENDING) {
            return Optional.empty();
        }

        final NodeRun nodeRun = locked.get();
        final Map<UUID, NodeRun> dependencies = this.dependenciesById(nodeRun.dependsOnNodeRunIds());
        if (this.hasInvalidDependencies(nodeRun, dependencies)) {
            this.nodeRunRepository.save(this.withFailed(
                    nodeRun,
                    new NodeRunFailure(INVALID_NODE_RUN_DEPENDENCY, "Node run dependency is invalid."),
                    Instant.now(this.clock)
            ));
            this.reconcileWorkflowRun(workflowRun, Instant.now(this.clock));
            return Optional.empty();
        }
        if (this.hasDependencyStatus(dependencies.values(), NodeRunStatus.FAILED, NodeRunStatus.BLOCKED, NodeRunStatus.CANCELLED)) {
            this.nodeRunRepository.save(this.withBlocked(nodeRun, Instant.now(this.clock)));
            this.reconcileWorkflowRun(workflowRun, Instant.now(this.clock));
            return Optional.empty();
        }
        if (this.hasDependencyStatus(dependencies.values(), NodeRunStatus.PENDING, NodeRunStatus.RUNNING)) {
            return Optional.empty();
        }

        final Optional<AgentDefinition> agent = this.agentDefinitionRepository.findById(nodeRun.sourceAgentId());
        if (agent.isEmpty()) {
            this.nodeRunRepository.save(this.withFailed(
                    nodeRun,
                    new NodeRunFailure(SOURCE_AGENT_NOT_FOUND, "Source agent was not found."),
                    Instant.now(this.clock)
            ));
            this.reconcileWorkflowRun(workflowRun, Instant.now(this.clock));
            return Optional.empty();
        }
        final Optional<NodeRunExecutionModel> executionModel = this.toExecutionModel(agent.get().model());
        if (executionModel.isEmpty()) {
            this.nodeRunRepository.save(this.withFailed(
                    nodeRun,
                    new NodeRunFailure(AGENT_MODEL_NOT_CONFIGURED, "Source agent model is not configured."),
                    Instant.now(this.clock)
            ));
            this.reconcileWorkflowRun(workflowRun, Instant.now(this.clock));
            return Optional.empty();
        }

        final Instant now = Instant.now(this.clock);
        final NodeRun running = this.nodeRunRepository.save(this.withRunning(nodeRun, executionModel.get(), now));
        if (workflowRun.status() == WorkflowRunStatus.QUEUED) {
            this.workflowRunRepository.saveLifecycle(new WorkflowRun(
                    workflowRun.id(),
                    workflowRun.projectId(),
                    workflowRun.sourceWorkflowId(),
                    workflowRun.workflowName(),
                    workflowRun.input(),
                    WorkflowRunStatus.RUNNING,
                    workflowRun.nodeRuns(),
                    workflowRun.createdAt(),
                    workflowRun.startedAt() == null ? now : workflowRun.startedAt(),
                    workflowRun.finishedAt()
            ));
        }

        return Optional.of(new NodeExecutionClaim(
                workflowRun.id(),
                running.id(),
                running.sourceAgentId(),
                workflowRun.input(),
                running.agentName(),
                running.agentInstructions(),
                running.agentOutputSchema(),
                running.executionModel(),
                this.dependencyOutputs(running.dependsOnNodeRunIds(), dependencies)
        ));
    }

    @Transactional
    public void succeed(final UUID nodeRunId, final NodeRunOutput output) {
        if (output == null) {
            throw new ConflictException(LIFECYCLE_CONFLICT, "Node run success output is required.");
        }
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return;
        }
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.status() == NodeRunStatus.SUCCEEDED && output.equals(nodeRun.output())) {
            return;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can succeed.");
        }
        this.nodeRunRepository.save(this.withSucceeded(nodeRun, output, Instant.now(this.clock)));
        this.reconcileWorkflowRun(target.workflowRun(), Instant.now(this.clock));
    }

    @Transactional
    public void fail(final UUID nodeRunId, final NodeRunFailure failure) {
        final NodeRunFailure normalized = this.normalizeFailure(failure);
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return;
        }
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.status() == NodeRunStatus.FAILED && normalized.equals(nodeRun.failure())) {
            return;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can fail.");
        }
        this.nodeRunRepository.save(this.withFailed(nodeRun, normalized, Instant.now(this.clock)));
        this.reconcileWorkflowRun(target.workflowRun(), Instant.now(this.clock));
    }

    private CompletionTarget lockCompletionTarget(final UUID nodeRunId) {
        final Optional<UUID> workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) {
            return null;
        }
        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId.get())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        final Optional<NodeRun> locked = this.nodeRunRepository.findByIdForUpdate(nodeRunId);
        return locked.map(nodeRun -> new CompletionTarget(workflowRun, nodeRun)).orElse(null);
    }

    private Map<UUID, NodeRun> dependenciesById(final List<UUID> dependencyIds) {
        if (dependencyIds == null || dependencyIds.isEmpty()) {
            return Map.of();
        }
        final Map<UUID, NodeRun> loaded = this.nodeRunRepository.findByIds(dependencyIds).stream()
                .collect(Collectors.toMap(NodeRun::id, Function.identity()));
        final Map<UUID, NodeRun> ordered = new LinkedHashMap<>();
        for (final UUID dependencyId : dependencyIds) {
            final NodeRun dependency = loaded.get(dependencyId);
            if (dependency != null) {
                ordered.put(dependencyId, dependency);
            }
        }
        return ordered;
    }

    private boolean hasInvalidDependencies(final NodeRun nodeRun, final Map<UUID, NodeRun> dependenciesById) {
        for (final UUID dependencyId : nodeRun.dependsOnNodeRunIds()) {
            final NodeRun dependency = dependenciesById.get(dependencyId);
            if (dependency == null || !nodeRun.workflowRunId().equals(dependency.workflowRunId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDependencyStatus(final Collection<NodeRun> dependencies, final NodeRunStatus... statuses) {
        for (final NodeRun dependency : dependencies) {
            for (final NodeRunStatus status : statuses) {
                if (dependency.status() == status) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<NodeDependencyOutput> dependencyOutputs(final List<UUID> dependencyIds,
                                                        final Map<UUID, NodeRun> dependenciesById) {
        return dependencyIds.stream()
                .map(dependencyId -> {
                    final NodeRun dependency = dependenciesById.get(dependencyId);
                    return new NodeDependencyOutput(dependencyId, dependency == null ? null : dependency.output());
                })
                .toList();
    }

    private Optional<NodeRunExecutionModel> toExecutionModel(final AgentModelSelection selection) {
        if (selection == null
                || this.isBlank(selection.providerId())
                || this.isBlank(selection.modelId())) {
            return Optional.empty();
        }
        return Optional.of(new NodeRunExecutionModel(
                selection.providerId(),
                selection.modelId(),
                this.isBlank(selection.effortId()) ? null : selection.effortId()
        ));
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private void reconcileWorkflowRun(final WorkflowRun lockedWorkflowRun, final Instant now) {
        if (lockedWorkflowRun.finishedAt() != null || this.isTerminal(lockedWorkflowRun.status())) {
            return;
        }
        final List<NodeRun> nodeRuns = this.nodeRunRepository.findByWorkflowRunId(lockedWorkflowRun.id());
        if (nodeRuns.stream().anyMatch(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING || nodeRun.status() == NodeRunStatus.RUNNING)) {
            return;
        }
        final boolean allSucceeded = nodeRuns.stream().allMatch(nodeRun -> nodeRun.status() == NodeRunStatus.SUCCEEDED);
        this.workflowRunRepository.saveLifecycle(new WorkflowRun(
                lockedWorkflowRun.id(),
                lockedWorkflowRun.projectId(),
                lockedWorkflowRun.sourceWorkflowId(),
                lockedWorkflowRun.workflowName(),
                lockedWorkflowRun.input(),
                allSucceeded ? WorkflowRunStatus.SUCCEEDED : WorkflowRunStatus.FAILED,
                lockedWorkflowRun.nodeRuns(),
                lockedWorkflowRun.createdAt(),
                lockedWorkflowRun.startedAt(),
                lockedWorkflowRun.finishedAt() == null ? now : lockedWorkflowRun.finishedAt()
        ));
    }

    private boolean isTerminal(final NodeRunStatus status) {
        return status == NodeRunStatus.SUCCEEDED
                || status == NodeRunStatus.FAILED
                || status == NodeRunStatus.BLOCKED
                || status == NodeRunStatus.CANCELLED;
    }

    private boolean isTerminal(final WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCEEDED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
    }

    private NodeRunFailure normalizeFailure(final NodeRunFailure failure) {
        if (failure == null || this.isBlank(failure.code())) {
            return new NodeRunFailure("NODE_RUN_FAILED", "Node run failed.");
        }
        return new NodeRunFailure(
                failure.code(),
                this.isBlank(failure.message()) ? "Node run failed." : failure.message()
        );
    }

    private NodeRun withRunning(final NodeRun nodeRun, final NodeRunExecutionModel executionModel, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                NodeRunStatus.RUNNING,
                nodeRun.output(),
                nodeRun.failure(),
                executionModel,
                nodeRun.createdAt(),
                nodeRun.startedAt() == null ? now : nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
    }

    private NodeRun withSucceeded(final NodeRun nodeRun, final NodeRunOutput output, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                NodeRunStatus.SUCCEEDED,
                output,
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt()
        );
    }

    private NodeRun withFailed(final NodeRun nodeRun, final NodeRunFailure failure, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                NodeRunStatus.FAILED,
                nodeRun.output(),
                failure,
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt()
        );
    }

    private NodeRun withBlocked(final NodeRun nodeRun, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.dependsOnNodeRunIds(),
                nodeRun.position(),
                NodeRunStatus.BLOCKED,
                nodeRun.output(),
                new NodeRunFailure("DEPENDENCY_NOT_SUCCEEDED", "A dependency did not complete successfully."),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt()
        );
    }

    private ConflictException conflict(final String message) {
        return new ConflictException(LIFECYCLE_CONFLICT, message);
    }

    private record CompletionTarget(WorkflowRun workflowRun, NodeRun nodeRun) {
    }
}
