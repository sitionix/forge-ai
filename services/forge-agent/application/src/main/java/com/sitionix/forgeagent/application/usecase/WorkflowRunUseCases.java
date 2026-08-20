package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.NodeRunFactory;
import com.sitionix.forgeagent.application.runtime.ExecutionBudgetPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowRunSnapshotBuilder;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowRunUseCases {

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunGraphRepository graphRepository;
    private final ExecutionFrameRepository executionFrameRepository;
    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunSnapshotBuilder snapshotBuilder;
    private final NodeRunFactory nodeRunFactory;
    private final ExecutionBudgetPolicy executionBudgetPolicy;
    private final Clock clock;

    @Transactional
    public WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command) {
        return this.createWorkflowRun(workflowId, command, null, List.of());
    }

    @Transactional
    public WorkflowRun createWorkflowRunForTask(final UUID workflowId, final CreateWorkflowRunCommand command,
                                                final UUID taskId, final List<UUID> repositoryIds) {
        if (taskId == null) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_TASK", "Workflow run taskId is required.");
        }
        return this.createWorkflowRun(workflowId, command, taskId, repositoryIds);
    }

    private WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command,
                                          final UUID taskId, final List<UUID> repositoryIds) {
        final String input = this.requireInput(command == null ? null : command.input());
        final Workflow workflow = this.workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        final UUID runId = UUID.randomUUID();
        final WorkflowRunGraph graph = this.snapshotBuilder.build(runId, workflow);
        final RunPort taskInputPort = this.requireTaskInputPort(graph);
        final RunNode entry = this.requireTaskInputNode(graph, taskInputPort);
        if (repositoryIds == null) {
            throw new ValidationException("TASK_RUN_REQUIRES_REPOSITORIES",
                    "Task-owned workflow run requires an explicit repository snapshot.");
        }
        final List<UUID> repositorySnapshot = List.copyOf(repositoryIds);
        if (taskId != null && repositorySnapshot.isEmpty()) {
            throw new ValidationException("TASK_RUN_REQUIRES_REPOSITORIES",
                    "Task-owned workflow run requires an explicit repository snapshot.");
        }
        if (repositorySnapshot.isEmpty()
                && graph.nodes().stream().anyMatch(node -> node.scopeMode() == NodeScopeMode.PER_SCOPE)) {
            throw new ValidationException("PER_SCOPE_RUN_REQUIRES_REPOSITORIES",
                    "A workflow containing any PER_SCOPE node requires selected repositories.");
        }
        final RunPort outputPort = this.requireTaskOutputPort(graph);
        final RunNode outputNode = graph.nodes().stream().filter(node -> node.sourceNodeId().equals(outputPort.sourceNodeId()))
                .findFirst().orElseThrow(() -> new ValidationException("WORKFLOW_OUTPUT_NOT_FOUND", "Workflow output node was not found."));
        if (repositorySnapshot.size() > 1 && outputNode.scopeMode() == NodeScopeMode.PER_SCOPE) {
            throw new ValidationException("AMBIGUOUS_PER_SCOPE_TASK_OUTPUT", "Task Output node must be GLOBAL for multi-repository runs.");
        }
        final Instant now = Instant.now(this.clock);
        final WorkflowRun run = this.workflowRunRepository.save(new WorkflowRun(
                runId,
                workflow.projectId(),
                workflow.id(),
                taskId,
                workflow.name(),
                input,
                WorkflowRunStatus.QUEUED,
                List.of(),
                List.of(),
                List.of(),
                graph,
                null,
                null,
                now,
                null,
                null,
                repositorySnapshot
        ));
        this.graphRepository.saveSnapshot(graph);
        final ExecutionFrame rootFrame = this.executionFrameRepository.save(new ExecutionFrame(UUID.randomUUID(), run.id(), null, now));
        final List<UUID> rootRepositories = entry.scopeMode() == NodeScopeMode.GLOBAL
                ? java.util.Collections.singletonList(null) : repositorySnapshot;
        final List<NodeRun> rootNodeRuns = rootRepositories.stream()
                .map(repositoryId -> this.createRootNodeRun(run, rootFrame, entry, taskInputPort.sourcePortId(), repositoryId))
                .toList();
        return new WorkflowRun(
                run.id(),
                run.projectId(),
                run.sourceWorkflowId(),
                run.taskId(),
                run.workflowName(),
                run.input(),
                run.status(),
                rootNodeRuns,
                List.of(),
                List.of(),
                graph,
                run.result(),
                run.resultSourceNodeRunId(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt(),
                run.repositoryIds()
        );
    }

    @Transactional(readOnly = true)
    public List<WorkflowRunSummary> listWorkflowRuns(final UUID workflowId) {
        this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        return this.workflowRunRepository.findSummariesBySourceWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public WorkflowRun getWorkflowRun(final UUID runId) {
        return this.workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
    }

    private String requireInput(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_INPUT", "Workflow run input is required.");
        }
        return candidate.trim();
    }

    private RunPort requireTaskInputPort(final WorkflowRunGraph graph) {
        if (graph.nodes().isEmpty()) {
            throw new ValidationException("WORKFLOW_ENTRY_NOT_FOUND", "Workflow entry node was not found.");
        }
        if (graph.taskInputPortId() == null) {
            throw new ValidationException("WORKFLOW_TASK_INPUT_REQUIRED", "Workflow task input port is required before execution.");
        }
        final RunPort port = graph.ports().stream()
                .filter(candidate -> graph.taskInputPortId().equals(candidate.sourcePortId()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("UNKNOWN_TASK_INPUT_PORT", "Workflow task input port must exist."));
        if (port.direction() != PortDirection.INPUT) {
            throw new ValidationException("INVALID_TASK_INPUT_PORT", "Workflow task input port must be an INPUT port.");
        }
        return port;
    }

    private RunPort requireTaskOutputPort(final WorkflowRunGraph graph) {
        if (graph.taskOutputPortId() == null) {
            throw new ValidationException("WORKFLOW_TASK_OUTPUT_REQUIRED", "Workflow task output port is required.");
        }
        final RunPort port = graph.ports().stream()
                .filter(candidate -> graph.taskOutputPortId().equals(candidate.sourcePortId()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("UNKNOWN_TASK_OUTPUT_PORT", "Workflow task output port must exist."));
        if (port.direction() != PortDirection.OUTPUT) {
            throw new ValidationException("INVALID_TASK_OUTPUT_PORT", "Workflow task output port must be an OUTPUT port.");
        }
        if (graph.connections().stream().anyMatch(connection -> graph.taskOutputPortId().equals(connection.sourceOutputPortId()))) {
            throw new ValidationException("TASK_OUTPUT_PORT_NOT_TERMINAL", "Workflow Task Output must not have downstream workflow connections.");
        }
        return port;
    }

    private RunNode requireTaskInputNode(final WorkflowRunGraph graph, final RunPort taskInputPort) {
        return graph.nodes().stream()
                .filter(node -> node.sourceNodeId().equals(taskInputPort.sourceNodeId()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("WORKFLOW_ENTRY_NOT_FOUND", "Workflow entry node was not found."));
    }

    private NodeRun createRootNodeRun(final WorkflowRun run, final ExecutionFrame rootFrame, final RunNode entry,
                                      final UUID taskInputPortId, final UUID repositoryId) {
        this.executionBudgetPolicy.assertNodeRunCanBeCreated(run);
        return this.nodeRunRepository.save(this.nodeRunFactory.root(run, rootFrame, entry, taskInputPortId, repositoryId));
    }
}
