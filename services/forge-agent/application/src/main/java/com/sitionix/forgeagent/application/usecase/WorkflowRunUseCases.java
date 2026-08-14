package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
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

    @Transactional
    public WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command) {
        this.requireInput(command == null ? null : command.input());
        this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        throw new ConflictException(
                "WORKFLOW_GRAPH_EXECUTION_NOT_SUPPORTED",
                "Execution of the port-aware workflow graph is not implemented yet."
        );
    }

    @Transactional
    public WorkflowRun createWorkflowRunForTask(final UUID workflowId, final CreateWorkflowRunCommand command, final UUID taskId) {
        if (taskId == null) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_TASK", "Workflow run taskId is required.");
        }
        return this.createWorkflowRun(workflowId, command);
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
}
