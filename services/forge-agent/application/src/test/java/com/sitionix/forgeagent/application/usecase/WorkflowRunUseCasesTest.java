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
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRunUseCasesTest {

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID workflowId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID runId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID taskId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID nodeId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID agentId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;

    private WorkflowRunUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new WorkflowRunUseCases(this.workflowRepository, this.workflowRunRepository);
    }

    @Test
    void createWorkflowRunFailsAtPortAwareGraphExecutionBoundary() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_GRAPH_EXECUTION_NOT_SUPPORTED");

        verify(this.workflowRepository).findById(this.workflowId);
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void taskTriggeredRunUsesSameExecutionBoundary() {
        final Workflow workflow = this.workflow();
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(this.workflowId, new CreateWorkflowRunCommand("Run it"), this.taskId))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_GRAPH_EXECUTION_NOT_SUPPORTED");

        verify(this.workflowRepository).findById(this.workflowId);
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void rejectsBlankInputBeforeExecutionBoundary() {
        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_INPUT");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void rejectsMissingTaskIdBeforeExecutionBoundary() {
        assertThatThrownBy(() -> this.useCases.createWorkflowRunForTask(this.workflowId, new CreateWorkflowRunCommand("Run it"), null))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_WORKFLOW_RUN_TASK");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void missingWorkflowReturnsControlledNotFound() {
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createWorkflowRun(this.workflowId, new CreateWorkflowRunCommand("Run it")))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NOT_FOUND");
        verify(this.workflowRunRepository, never()).save(any());
    }

    @Test
    void listDelegatesDeterministicRepositoryHistory() {
        final Workflow workflow = this.workflow();
        final WorkflowRunSummary summary = new WorkflowRunSummary(
                this.runId,
                this.workflowId,
                this.taskId,
                "Full Testing",
                WorkflowRunStatus.QUEUED,
                Instant.parse("2026-08-10T12:00:00Z"),
                null,
                null
        );
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(workflow));
        when(this.workflowRunRepository.findSummariesBySourceWorkflowId(this.workflowId)).thenReturn(List.of(summary));

        assertThat(this.useCases.listWorkflowRuns(this.workflowId)).containsExactly(summary);
    }

    @Test
    void getWorkflowRunReadsHistoricalRuns() {
        final WorkflowRun run = new WorkflowRun(
                this.runId,
                this.projectId,
                this.workflowId,
                this.taskId,
                "Full Testing",
                "Input",
                WorkflowRunStatus.QUEUED,
                List.of(),
                Instant.EPOCH,
                null,
                null
        );
        when(this.workflowRunRepository.findById(this.runId)).thenReturn(Optional.of(run));

        assertThat(this.useCases.getWorkflowRun(this.runId)).isEqualTo(run);
    }

    @Test
    void missingWorkflowRunReturnsControlledNotFound() {
        when(this.workflowRunRepository.findById(this.runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.getWorkflowRun(this.runId))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_RUN_NOT_FOUND");
    }

    private Workflow workflow() {
        return new Workflow(
                this.workflowId,
                this.projectId,
                "Full Testing",
                "full testing",
                List.of(new Node(this.nodeId, this.agentId, new NodePosition(1.0, 2.0))),
                List.of(),
                Instant.EPOCH,
                Instant.EPOCH
        );
    }
}
