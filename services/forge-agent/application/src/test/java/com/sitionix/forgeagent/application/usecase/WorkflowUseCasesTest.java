package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.graph.NodeGraphValidator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID workflowId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID nodeA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID nodeB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID agentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    private WorkflowUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new WorkflowUseCases(
                this.projectRepository,
                this.agentDefinitionRepository,
                this.workflowRepository,
                new NodeGraphValidator(),
                CLOCK
        );
    }

    @Test
    void createsEmptyWorkflow() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final Workflow workflow = this.useCases.createWorkflow(this.projectId, new CreateWorkflowCommand(" Full Testing "));

        assertThat(workflow.name()).isEqualTo("Full Testing");
        assertThat(workflow.nodes()).isEmpty();
        assertThat(workflow.createdAt()).isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
    }

    @Test
    void rejectsDuplicateWorkflowName() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.workflowRepository.existsByProjectIdAndNormalizedName(this.projectId, "full testing")).thenReturn(true);

        assertThatThrownBy(() -> this.useCases.createWorkflow(this.projectId, new CreateWorkflowCommand("Full Testing")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("DUPLICATE_WORKFLOW_NAME");
    }

    @Test
    void updateLocksWorkflowBeforeReloadAndValidatesFinalGraphBeforeSave() {
        final Workflow current = this.workflow(List.of());
        final Node first = new Node(this.nodeA, this.agentId, List.of(), new NodePosition(1.0, 2.0));
        final Node second = new Node(this.nodeB, this.agentId, List.of(this.nodeA), new NodePosition(3.0, 4.0));
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(current), Optional.of(current));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(current));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));
        when(this.workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final Workflow saved = this.useCases.updateWorkflow(
                this.workflowId,
                new SaveWorkflowCommand("Full Testing", List.of(first, second))
        );

        assertThat(saved.nodes()).containsExactly(first, second);
        final InOrder order = org.mockito.Mockito.inOrder(this.workflowRepository, this.agentDefinitionRepository);
        order.verify(this.workflowRepository).findById(this.workflowId);
        order.verify(this.workflowRepository).findByIdForUpdate(this.workflowId);
        order.verify(this.workflowRepository).findById(this.workflowId);
        order.verify(this.agentDefinitionRepository).findByIds(any());
        order.verify(this.workflowRepository).save(any());
    }

    @Test
    void failedUpdateDoesNotPersist() {
        final Workflow current = this.workflow(List.of(new Node(this.nodeA, this.agentId, List.of(), new NodePosition(1.0, 2.0))));
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.of(current), Optional.of(current));
        when(this.workflowRepository.findByIdForUpdate(this.workflowId)).thenReturn(Optional.of(current));
        when(this.agentDefinitionRepository.findByIds(any())).thenReturn(List.of(this.agent()));

        assertThatThrownBy(() -> this.useCases.updateWorkflow(
                this.workflowId,
                new SaveWorkflowCommand("Full Testing", List.of(
                        new Node(this.nodeA, this.agentId, List.of(this.nodeB), new NodePosition(1.0, 2.0)),
                        new Node(this.nodeB, this.agentId, List.of(this.nodeA), new NodePosition(3.0, 4.0))
                ))
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_GRAPH_CYCLE");
        verify(this.workflowRepository, never()).save(any());
    }

    @Test
    void getMissingWorkflowThrowsControlledError() {
        when(this.workflowRepository.findById(this.workflowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.getWorkflow(this.workflowId))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NOT_FOUND");
    }

    private Project project() {
        return new Project(this.projectId, "Sitionix", "sitionix", Instant.EPOCH, Instant.EPOCH);
    }

    private AgentDefinition agent() {
        return new AgentDefinition(this.agentId, this.projectId, "Agent", "agent", "Instructions", OUTPUT_SCHEMA, Instant.EPOCH, Instant.EPOCH);
    }

    private Workflow workflow(final List<Node> nodes) {
        return new Workflow(this.workflowId, this.projectId, "Full Testing", "full testing", nodes, Instant.EPOCH, Instant.EPOCH);
    }
}
