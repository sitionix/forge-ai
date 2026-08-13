package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.ProjectTaskSummary;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectTaskRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectTaskUseCases {

    private static final int MAX_TITLE_LENGTH = 120;

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunUseCases workflowRunUseCases;
    private final Clock clock;

    @Transactional
    public ProjectTaskDetails createProjectTask(final UUID projectId, final CreateProjectTaskCommand command) {
        final String title = this.requireTitle(command == null ? null : command.title());
        final String input = this.requireInput(command == null ? null : command.input());
        final UUID workflowId = this.requireWorkflowId(command == null ? null : command.workflowId());
        this.requireProject(projectId);
        final Workflow workflow = this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        if (!projectId.equals(workflow.projectId())) {
            throw new ValidationException("WORKFLOW_PROJECT_MISMATCH", "Workflow does not belong to the project.");
        }

        final Instant now = Instant.now(this.clock);
        final ProjectTask task = this.projectTaskRepository.save(new ProjectTask(
                UUID.randomUUID(),
                projectId,
                title,
                input,
                workflowId,
                now,
                now
        ));
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRunForTask(
                workflowId,
                new CreateWorkflowRunCommand(input),
                task.id()
        );
        return this.toDetails(task, List.of(this.toSummary(run)));
    }

    @Transactional(readOnly = true)
    public List<ProjectTaskSummary> listProjectTasks(final UUID projectId) {
        this.requireProject(projectId);
        return this.projectTaskRepository.findByProjectId(projectId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectTaskDetails getProjectTask(final UUID taskId) {
        final ProjectTask task = this.projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("PROJECT_TASK_NOT_FOUND", "Project task was not found."));
        return this.toDetails(task, this.workflowRunRepository.findSummariesByTaskId(task.id()));
    }

    private void requireProject(final UUID projectId) {
        if (projectId == null || this.projectRepository.findById(projectId).isEmpty()) {
            throw new NotFoundException("PROJECT_NOT_FOUND", "Project was not found.");
        }
    }

    private UUID requireWorkflowId(final UUID workflowId) {
        if (workflowId == null) {
            throw new ValidationException("INVALID_PROJECT_TASK_WORKFLOW", "Task workflowId is required.");
        }
        return workflowId;
    }

    private String requireTitle(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_PROJECT_TASK_TITLE", "Task title is required.");
        }
        final String trimmed = candidate.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("INVALID_PROJECT_TASK_TITLE", "Task title must be at most 120 characters.");
        }
        return trimmed;
    }

    private String requireInput(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_PROJECT_TASK_INPUT", "Task input is required.");
        }
        return candidate.trim();
    }

    private ProjectTaskSummary toSummary(final ProjectTask task) {
        final WorkflowRunSummary latestRun = this.workflowRunRepository.findSummariesByTaskId(task.id()).stream()
                .max(Comparator.comparing(WorkflowRunSummary::createdAt).thenComparing(WorkflowRunSummary::id))
                .orElse(null);
        return new ProjectTaskSummary(
                task.id(),
                task.projectId(),
                task.title(),
                task.workflowId(),
                latestRun == null ? null : latestRun.workflowName(),
                latestRun == null ? null : latestRun.id(),
                latestRun == null ? null : latestRun.status(),
                task.createdAt(),
                task.updatedAt()
        );
    }

    private ProjectTaskDetails toDetails(final ProjectTask task, final List<WorkflowRunSummary> runs) {
        return new ProjectTaskDetails(
                task.id(),
                task.projectId(),
                task.title(),
                task.input(),
                task.workflowId(),
                runs,
                task.createdAt(),
                task.updatedAt()
        );
    }

    private WorkflowRunSummary toSummary(final WorkflowRun run) {
        return new WorkflowRunSummary(
                run.id(),
                run.sourceWorkflowId(),
                run.taskId(),
                run.workflowName(),
                run.status(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
        );
    }
}
