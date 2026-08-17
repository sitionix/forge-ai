package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.ProjectTaskPage;
import com.sitionix.forgeagent.domain.model.ProjectTaskSummary;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectTaskRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectTaskUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TASK_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private WorkflowRunUseCases workflowRunUseCases;

    private ProjectTaskUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new ProjectTaskUseCases(
                this.projectRepository,
                this.workflowRepository,
                this.projectTaskRepository,
                this.workflowRunRepository,
                this.workflowRunUseCases,
                CLOCK
        );
    }

    @Test
    void createsTaskAndExactlyOneInitialWorkflowRun() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project(PROJECT_ID)));
        when(this.workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(this.workflow(PROJECT_ID)));
        when(this.projectTaskRepository.save(any())).thenReturn(this.task("Simple analysis", "Find X and explain Y"));
        when(this.workflowRunUseCases.createWorkflowRunForTask(any(), any(), any())).thenReturn(this.run(TASK_ID, "Find X and explain Y"));

        final ProjectTaskDetails created = this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand(
                "  Simple analysis  ",
                "  Find X and explain Y  ",
                WORKFLOW_ID
        ));

        assertThat(created.id()).isEqualTo(TASK_ID);
        assertThat(created.projectId()).isEqualTo(PROJECT_ID);
        assertThat(created.title()).isEqualTo("Simple analysis");
        assertThat(created.input()).isEqualTo("Find X and explain Y");
        assertThat(created.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(created.runs()).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(RUN_ID);
            assertThat(run.taskId()).isEqualTo(TASK_ID);
            assertThat(run.status()).isEqualTo(WorkflowRunStatus.QUEUED);
        });

        final ArgumentCaptor<ProjectTask> taskCaptor = ArgumentCaptor.forClass(ProjectTask.class);
        verify(this.projectTaskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(taskCaptor.getValue().title()).isEqualTo("Simple analysis");
        assertThat(taskCaptor.getValue().input()).isEqualTo("Find X and explain Y");
        assertThat(taskCaptor.getValue().workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(taskCaptor.getValue().createdAt()).isEqualTo(NOW);
        assertThat(taskCaptor.getValue().updatedAt()).isEqualTo(NOW);

        final ArgumentCaptor<CreateWorkflowRunCommand> runCommand = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(this.workflowRunUseCases).createWorkflowRunForTask(org.mockito.Mockito.eq(WORKFLOW_ID), runCommand.capture(), org.mockito.Mockito.eq(TASK_ID));
        assertThat(runCommand.getValue().input()).isEqualTo("Find X and explain Y");
    }

    @Test
    void rejectsCrossProjectWorkflow() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project(PROJECT_ID)));
        when(this.workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(this.workflow(OTHER_PROJECT_ID)));

        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", "Input", WORKFLOW_ID)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_PROJECT_MISMATCH");
        verify(this.projectTaskRepository, never()).save(any());
        verify(this.workflowRunUseCases, never()).createWorkflowRunForTask(any(), any(), any());
    }

    @Test
    void rejectsMissingProject() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", "Input", WORKFLOW_ID)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("PROJECT_NOT_FOUND");
        verify(this.workflowRepository, never()).findById(any());
        verify(this.projectTaskRepository, never()).save(any());
    }

    @Test
    void rejectsMissingWorkflow() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project(PROJECT_ID)));
        when(this.workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", "Input", WORKFLOW_ID)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NOT_FOUND");
        verify(this.projectTaskRepository, never()).save(any());
    }

    @Test
    void rejectsInvalidRequestFields() {
        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand(" ", "Input", WORKFLOW_ID)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_PROJECT_TASK_TITLE");
        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("a".repeat(121), "Input", WORKFLOW_ID)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_PROJECT_TASK_TITLE");
        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", " ", WORKFLOW_ID)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_PROJECT_TASK_INPUT");
        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", "Input", null)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_PROJECT_TASK_WORKFLOW");
        verify(this.projectTaskRepository, never()).save(any());
    }

    @Test
    void workflowRunCreationFailurePropagatesSoTransactionCanRollBackTask() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project(PROJECT_ID)));
        when(this.workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(this.workflow(PROJECT_ID)));
        when(this.projectTaskRepository.save(any())).thenReturn(this.task("Title", "Input"));
        when(this.workflowRunUseCases.createWorkflowRunForTask(WORKFLOW_ID, new CreateWorkflowRunCommand("Input"), TASK_ID))
                .thenThrow(new ConflictException("EMPTY_WORKFLOW", "Workflow must contain at least one node before a run can be created."));

        assertThatThrownBy(() -> this.useCases.createProjectTask(PROJECT_ID, new CreateProjectTaskCommand("Title", "Input", WORKFLOW_ID)))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("EMPTY_WORKFLOW");
    }

    @Test
    void listSummariesDerivesStatusFromLatestWorkflowRun() {
        final ProjectTask task = this.task("Title", "Input");
        final WorkflowRunSummary older = this.summary(UUID.fromString("44444444-4444-4444-8444-444444444441"), TASK_ID, Instant.parse("2026-08-10T12:00:00Z"), WorkflowRunStatus.FAILED);
        final WorkflowRunSummary latest = this.summary(UUID.fromString("44444444-4444-4444-8444-444444444442"), TASK_ID, Instant.parse("2026-08-10T12:01:00Z"), WorkflowRunStatus.QUEUED);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project(PROJECT_ID)));
        when(this.projectTaskRepository.findPageByProjectId(PROJECT_ID, 0, 20)).thenReturn(new ProjectTaskPage(List.of(task), 0, 20, 1, 1));
        when(this.workflowRunRepository.findSummariesByTaskId(TASK_ID)).thenReturn(List.of(latest, older));

        final List<ProjectTaskSummary> summaries = this.useCases.listProjectTasks(PROJECT_ID, 0, 20).items();

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.latestWorkflowRunId()).isEqualTo(latest.id());
            assertThat(summary.workflowName()).isEqualTo("Full Testing");
            assertThat(summary.executionStatus()).isEqualTo(WorkflowRunStatus.QUEUED);
        });
    }

    @Test
    void getDetailsReturnsAllTaskRuns() {
        final ProjectTask task = this.task("Title", "Input");
        final WorkflowRunSummary run = this.summary(RUN_ID, TASK_ID, NOW, WorkflowRunStatus.QUEUED);
        when(this.projectTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(this.workflowRunRepository.findSummariesByTaskId(TASK_ID)).thenReturn(List.of(run));

        final ProjectTaskDetails details = this.useCases.getProjectTask(TASK_ID);

        assertThat(details.runs()).containsExactly(run);
    }

    @Test
    void getDetailsExposesLatestSuccessfulWorkflowRunResult() {
        final ProjectTask task = this.task("Title", "Input");
        final NodeRunOutput result = new NodeRunOutput("{\"answer\":\"done\"}");
        when(this.projectTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(this.workflowRunRepository.findSummariesByTaskId(TASK_ID)).thenReturn(List.of(
                this.summary(RUN_ID, TASK_ID, NOW, WorkflowRunStatus.SUCCEEDED)
        ));
        when(this.workflowRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.of(this.run(
                RUN_ID,
                WorkflowRunStatus.SUCCEEDED,
                result
        )));

        final ProjectTaskDetails details = this.useCases.getProjectTask(TASK_ID);

        assertThat(details.result()).isEqualTo(result);
    }

    @Test
    void getDetailsDoesNotFallBackToOlderResultWhenLatestRunIsNotSuccessful() {
        final ProjectTask task = this.task("Title", "Input");
        final UUID newerRunId = UUID.fromString("44444444-4444-4444-8444-444444444445");
        when(this.projectTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(this.workflowRunRepository.findSummariesByTaskId(TASK_ID)).thenReturn(List.of(
                this.summary(newerRunId, TASK_ID, NOW.plusSeconds(60), WorkflowRunStatus.RUNNING),
                this.summary(RUN_ID, TASK_ID, NOW, WorkflowRunStatus.SUCCEEDED)
        ));
        when(this.workflowRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.of(this.run(
                newerRunId,
                WorkflowRunStatus.RUNNING,
                null
        )));

        final ProjectTaskDetails details = this.useCases.getProjectTask(TASK_ID);

        assertThat(details.result()).isNull();
    }

    private Project project(final UUID projectId) {
        return new Project(projectId, "Sitionix", "sitionix", Instant.EPOCH, Instant.EPOCH);
    }

    private Workflow workflow(final UUID projectId) {
        return new Workflow(WORKFLOW_ID, projectId, "Full Testing", "full testing", List.of(), List.of(), null, Instant.EPOCH, Instant.EPOCH);
    }

    private ProjectTask task(final String title, final String input) {
        return new ProjectTask(TASK_ID, PROJECT_ID, title, input, WORKFLOW_ID, NOW, NOW);
    }

    private WorkflowRun run(final UUID taskId, final String input) {
        return new WorkflowRun(RUN_ID, PROJECT_ID, WORKFLOW_ID, taskId, "Full Testing", input, WorkflowRunStatus.QUEUED, List.of(), NOW, null, null);
    }

    private WorkflowRun run(final UUID runId, final WorkflowRunStatus status, final NodeRunOutput result) {
        return new WorkflowRun(
                runId,
                PROJECT_ID,
                WORKFLOW_ID,
                TASK_ID,
                "Full Testing",
                "Input",
                status,
                List.of(),
                List.of(),
                List.of(),
                null,
                result,
                result == null ? null : UUID.fromString("55555555-5555-4555-8555-555555555555"),
                NOW,
                status == WorkflowRunStatus.QUEUED ? null : NOW,
                status == WorkflowRunStatus.SUCCEEDED ? NOW.plusSeconds(1) : null
        );
    }

    private WorkflowRunSummary summary(final UUID runId, final UUID taskId, final Instant createdAt, final WorkflowRunStatus status) {
        return new WorkflowRunSummary(runId, WORKFLOW_ID, taskId, "Full Testing", status, createdAt, null, null);
    }
}
