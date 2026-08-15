package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.NodeRunFactory;
import com.sitionix.forgeagent.application.runtime.ExecutionBudgetPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowEntrySelector;
import com.sitionix.forgeagent.application.runtime.WorkflowRunSnapshotBuilder;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.RunNode;
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
    private final WorkflowEntrySelector entrySelector;
    private final NodeRunFactory nodeRunFactory;
    private final ExecutionBudgetPolicy executionBudgetPolicy;
    private final Clock clock;

    @Transactional
    public WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command) {
        return this.createWorkflowRun(workflowId, command, null);
    }

    @Transactional
    public WorkflowRun createWorkflowRunForTask(final UUID workflowId, final CreateWorkflowRunCommand command, final UUID taskId) {
        if (taskId == null) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_TASK", "Workflow run taskId is required.");
        }
        return this.createWorkflowRun(workflowId, command, taskId);
    }

    private WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command, final UUID taskId) {
        final String input = this.requireInput(command == null ? null : command.input());
        final Workflow workflow = this.workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        final UUID runId = UUID.randomUUID();
        final WorkflowRunGraph graph = this.snapshotBuilder.build(runId, workflow);
        final List<RunNode> entries = this.entrySelector.selectEntries(graph);
        if (graph.nodes().isEmpty() || entries.isEmpty()) {
            throw new ValidationException("WORKFLOW_ENTRY_NOT_FOUND", "Workflow entry node was not found.");
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
                now,
                null,
                null
        ));
        this.graphRepository.saveSnapshot(graph);
        final ExecutionFrame rootFrame = this.executionFrameRepository.save(new ExecutionFrame(UUID.randomUUID(), run.id(), null, now));
        final List<NodeRun> rootNodeRuns = entries.stream()
                .map(entry -> this.createRootNodeRun(run, rootFrame, entry))
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
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
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

    private NodeRun createRootNodeRun(final WorkflowRun run, final ExecutionFrame rootFrame, final RunNode entry) {
        this.executionBudgetPolicy.assertNodeRunCanBeCreated(run);
        return this.nodeRunRepository.save(this.nodeRunFactory.root(run, rootFrame, entry));
    }
}
