package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresWorkflowRepositoryTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_WORKFLOW_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID NODE_C = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock
    private SpringDataWorkflowRepository workflowRepository;
    @Mock
    private SpringDataWorkflowNodeRepository nodeRepository;

    private PostgresWorkflowRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new PostgresWorkflowRepository(this.workflowRepository, this.nodeRepository);
        when(this.workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.nodeRepository.findByWorkflowIdOrderByIdAsc(WORKFLOW_ID)).thenReturn(List.of());
    }

    @Test
    void existingNodeIsRetainedAndReconciledWhenStillDesired() {
        final WorkflowNodeEntity currentA = this.entity(WORKFLOW_ID, NODE_A, AGENT_ID, List.of(), 1.0, 2.0);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(currentA));

        this.repository.save(this.workflow(List.of(this.node(NODE_A, List.of(), 10.0, 20.0))));

        verify(this.nodeRepository, never()).deleteAll(any());
        final List<WorkflowNodeEntity> saved = this.savedNodes();
        assertThat(saved).hasSize(1);
        final WorkflowNodeEntity savedA = saved.get(0);
        assertThat(savedA).isSameAs(currentA);
        assertThat(savedA.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(savedA.getId()).isEqualTo(NODE_A);
        assertThat(savedA.getTargetId()).isEqualTo(AGENT_ID);
        assertThat(savedA.getDependsOnNodeIds()).isEmpty();
        assertThat(savedA.getPositionX()).isEqualTo(10.0);
        assertThat(savedA.getPositionY()).isEqualTo(20.0);
    }

    @Test
    void removedNodeIsDeletedWithoutDeletingRetainedNode() {
        final WorkflowNodeEntity currentA = this.entity(WORKFLOW_ID, NODE_A, AGENT_ID, List.of(), 1.0, 2.0);
        final WorkflowNodeEntity currentB = this.entity(WORKFLOW_ID, NODE_B, AGENT_ID, List.of(NODE_A), 3.0, 4.0);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(currentA, currentB));

        this.repository.save(this.workflow(List.of(this.node(NODE_A, List.of(), 1.0, 2.0))));

        verify(this.nodeRepository).deleteAll(List.of(currentB));
        assertThat(this.savedNodes()).containsExactly(currentA);
    }

    @Test
    void addedNodeIsPersistedWithWorkflowOwnershipAndGraphFields() {
        final WorkflowNodeEntity currentA = this.entity(WORKFLOW_ID, NODE_A, AGENT_ID, List.of(), 1.0, 2.0);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(currentA));

        this.repository.save(this.workflow(List.of(
                this.node(NODE_A, List.of(), 1.0, 2.0),
                this.node(NODE_C, List.of(NODE_A), 5.0, 6.0)
        )));

        final List<WorkflowNodeEntity> saved = this.savedNodes();
        assertThat(saved).hasSize(2);
        final WorkflowNodeEntity savedC = saved.stream()
                .filter(node -> node.getId().equals(NODE_C))
                .findFirst()
                .orElseThrow();
        assertThat(savedC.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(savedC.getId()).isEqualTo(NODE_C);
        assertThat(savedC.getTargetId()).isEqualTo(AGENT_ID);
        assertThat(savedC.getDependsOnNodeIds()).containsExactly(NODE_A);
        assertThat(savedC.getPositionX()).isEqualTo(5.0);
        assertThat(savedC.getPositionY()).isEqualTo(6.0);
    }

    @Test
    void mixedReplacementDeletesRemovedRetainsChangedAndInsertsNew() {
        final WorkflowNodeEntity currentA = this.entity(WORKFLOW_ID, NODE_A, AGENT_ID, List.of(), 1.0, 2.0);
        final WorkflowNodeEntity currentB = this.entity(WORKFLOW_ID, NODE_B, AGENT_ID, List.of(NODE_A), 3.0, 4.0);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(currentA, currentB));

        this.repository.save(this.workflow(List.of(
                this.node(NODE_A, List.of(NODE_C), 10.0, 20.0),
                this.node(NODE_C, List.of(), 30.0, 40.0)
        )));

        verify(this.nodeRepository).deleteAll(List.of(currentB));
        final List<WorkflowNodeEntity> saved = this.savedNodes();
        assertThat(saved).hasSize(2);
        assertThat(saved).anySatisfy(node -> {
            assertThat(node).isSameAs(currentA);
            assertThat(node.getDependsOnNodeIds()).containsExactly(NODE_C);
            assertThat(node.getPositionX()).isEqualTo(10.0);
            assertThat(node.getPositionY()).isEqualTo(20.0);
        });
        assertThat(saved).anySatisfy(node -> {
            assertThat(node.getId()).isEqualTo(NODE_C);
            assertThat(node.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(node.getPositionX()).isEqualTo(30.0);
            assertThat(node.getPositionY()).isEqualTo(40.0);
        });
    }

    @Test
    void reconciliationUsesCurrentWorkflowOwnershipForNodeIdentity() {
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(
                this.entity(WORKFLOW_ID, NODE_A, AGENT_ID, List.of(), 1.0, 2.0)
        ));

        this.repository.save(this.workflow(List.of(this.node(NODE_A, List.of(), 7.0, 8.0))));

        verify(this.nodeRepository).findByWorkflowId(WORKFLOW_ID);
        verify(this.nodeRepository, never()).findByWorkflowId(OTHER_WORKFLOW_ID);
        this.savedNodes().forEach(node -> assertThat(node.getWorkflowId()).isEqualTo(WORKFLOW_ID));
    }

    private Workflow workflow(final List<Node> nodes) {
        return new Workflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", "full testing", nodes, NOW, NOW);
    }

    private Node node(final UUID id, final List<UUID> dependsOnNodeIds, final double x, final double y) {
        return new Node(id, AGENT_ID, dependsOnNodeIds, new NodePosition(x, y));
    }

    private WorkflowNodeEntity entity(final UUID workflowId,
                                      final UUID id,
                                      final UUID targetId,
                                      final List<UUID> dependsOnNodeIds,
                                      final double x,
                                      final double y) {
        final WorkflowNodeEntity entity = new WorkflowNodeEntity();
        entity.setWorkflowId(workflowId);
        entity.setId(id);
        entity.setTargetId(targetId);
        entity.setDependsOnNodeIds(dependsOnNodeIds.toArray(UUID[]::new));
        entity.setPositionX(x);
        entity.setPositionY(y);
        return entity;
    }

    private List<WorkflowNodeEntity> savedNodes() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<WorkflowNodeEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(this.nodeRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }
}
