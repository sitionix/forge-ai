package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodePortEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodePortRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private static final UUID INPUT_A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_B = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID OUTPUT_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID CONNECTION_AB = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTION_BA = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock
    private SpringDataWorkflowRepository workflowRepository;
    @Mock
    private SpringDataWorkflowNodeRepository nodeRepository;
    @Mock
    private SpringDataWorkflowNodePortRepository portRepository;
    @Mock
    private SpringDataWorkflowConnectionRepository connectionRepository;

    private PostgresWorkflowRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new PostgresWorkflowRepository(this.workflowRepository, this.nodeRepository, this.portRepository, this.connectionRepository);
        lenient().when(this.workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(this.nodeRepository.findByWorkflowIdOrderByIdAsc(WORKFLOW_ID)).thenReturn(List.of());
        lenient().when(this.portRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of());
        lenient().when(this.portRepository.findByWorkflowIdOrderByNodeIdAscPortOrderAsc(WORKFLOW_ID)).thenReturn(List.of());
        lenient().when(this.portRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(this.connectionRepository.findAllById(any())).thenReturn(List.of());
    }

    @Test
    void savesNodesPortsAndConnectionsByStableId() {
        final Workflow workflow = this.workflow(List.of(this.nodeA(), this.nodeB()), List.of(new WorkflowConnection(CONNECTION_AB, OUTPUT_A, INPUT_B)));
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of());

        this.repository.save(workflow);

        assertThat(this.savedNodes()).extracting(WorkflowNodeEntity::getId).containsExactly(NODE_A, NODE_B);
        assertThat(this.savedPorts()).extracting(WorkflowNodePortEntity::getId).containsExactly(INPUT_A, OUTPUT_A, INPUT_B, OUTPUT_B);
        assertThat(this.savedConnections())
                .extracting(WorkflowConnectionEntity::getId, WorkflowConnectionEntity::getSourceOutputPortId, WorkflowConnectionEntity::getTargetInputPortId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CONNECTION_AB, OUTPUT_A, INPUT_B));
    }

    @Test
    void reconcilesRemovedConnectionAndPreservesExistingConnectionIdentity() {
        final WorkflowConnectionEntity current = this.connectionEntity(CONNECTION_AB, OUTPUT_A, INPUT_B);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(this.nodeEntity(NODE_A), this.nodeEntity(NODE_B)));
        when(this.portRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(
                this.portEntity(INPUT_A, NODE_A, "INPUT", "Input", "Input.", 0),
                this.portEntity(OUTPUT_A, NODE_A, "OUTPUT", "Output", "Output.", 0),
                this.portEntity(INPUT_B, NODE_B, "INPUT", "Input", "Input.", 0),
                this.portEntity(OUTPUT_B, NODE_B, "OUTPUT", "Output", "Output.", 0)
        ));
        when(this.connectionRepository.findBySourceOutputPortIdIn(any())).thenReturn(List.of(current));

        this.repository.save(this.workflow(List.of(this.nodeA(), this.nodeB()), List.of(new WorkflowConnection(CONNECTION_BA, OUTPUT_B, INPUT_A))));

        verify(this.connectionRepository).deleteAll(List.of(current));
        assertThat(this.savedConnections())
                .extracting(WorkflowConnectionEntity::getId, WorkflowConnectionEntity::getSourceOutputPortId, WorkflowConnectionEntity::getTargetInputPortId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CONNECTION_BA, OUTPUT_B, INPUT_A));
    }

    @Test
    void loadsWorkflowWithNodesPortsAndConnections() {
        final WorkflowEntity workflowEntity = new WorkflowEntity();
        workflowEntity.setId(WORKFLOW_ID);
        workflowEntity.setProjectId(PROJECT_ID);
        workflowEntity.setName("Full Testing");
        workflowEntity.setNormalizedName("full testing");
        workflowEntity.setCreatedAt(NOW);
        workflowEntity.setUpdatedAt(NOW);
        when(this.workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowEntity));
        when(this.nodeRepository.findByWorkflowIdOrderByIdAsc(WORKFLOW_ID)).thenReturn(List.of(this.nodeEntity(NODE_A), this.nodeEntity(NODE_B)));
        when(this.portRepository.findByWorkflowIdOrderByNodeIdAscPortOrderAsc(WORKFLOW_ID)).thenReturn(List.of(
                this.portEntity(INPUT_A, NODE_A, "INPUT", "Input", "Input.", 0),
                this.portEntity(OUTPUT_A, NODE_A, "OUTPUT", "Output", "Output.", 0),
                this.portEntity(INPUT_B, NODE_B, "INPUT", "Input", "Input.", 0),
                this.portEntity(OUTPUT_B, NODE_B, "OUTPUT", "Output", "Output.", 0)
        ));
        when(this.connectionRepository.findBySourceOutputPortIdIn(any())).thenReturn(List.of(this.connectionEntity(CONNECTION_AB, OUTPUT_A, INPUT_B)));

        final Workflow loaded = this.repository.findById(WORKFLOW_ID).orElseThrow();

        assertThat(loaded.nodes()).hasSize(2);
        assertThat(loaded.connections()).containsExactly(new WorkflowConnection(CONNECTION_AB, OUTPUT_A, INPUT_B));
    }

    @Test
    void removedNodeIsDeletedAndConnectionDeletionIsLeftToCascadeWhenNoDesiredConnectionRemains() {
        final WorkflowNodeEntity currentA = this.nodeEntity(NODE_A);
        final WorkflowNodeEntity currentB = this.nodeEntity(NODE_B);
        when(this.nodeRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of(currentA, currentB));

        this.repository.save(this.workflow(List.of(this.nodeA()), List.of()));

        verify(this.nodeRepository).deleteAll(List.of(currentB));
        verify(this.connectionRepository, never()).deleteAll(any());
    }

    @Test
    void existingSameWorkflowPortUuidIsAllowed() {
        when(this.portRepository.findAllById(any())).thenReturn(List.of(this.portEntity(INPUT_A, WORKFLOW_ID, NODE_A, "INPUT", "Ready", "Ready.", 0)));

        this.repository.save(this.workflow(List.of(this.nodeA()), List.of()));

        assertThat(this.savedPorts())
                .extracting(WorkflowNodePortEntity::getId, WorkflowNodePortEntity::getWorkflowId)
                .contains(org.assertj.core.groups.Tuple.tuple(INPUT_A, WORKFLOW_ID));
        verify(this.portRepository).findAllById(Set.of(INPUT_A, OUTPUT_A));
    }

    @Test
    void existingDifferentWorkflowPortUuidIsRejectedBeforeChildMutation() {
        when(this.portRepository.findAllById(any())).thenReturn(List.of(this.portEntity(INPUT_A, OTHER_WORKFLOW_ID, NODE_A, "INPUT", "Input", "Input.", 0)));

        assertThatThrownBy(() -> this.repository.save(this.workflow(List.of(this.nodeA()), List.of())))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_NODE_PORT_ID_IN_USE");

        verify(this.portRepository).findAllById(Set.of(INPUT_A, OUTPUT_A));
        verify(this.nodeRepository, never()).findByWorkflowId(WORKFLOW_ID);
        verify(this.nodeRepository, never()).saveAll(any());
        verify(this.portRepository, never()).saveAll(any());
        verify(this.connectionRepository, never()).saveAll(any());
    }

    @Test
    void existingSameWorkflowConnectionUuidIsAllowed() {
        final WorkflowConnectionEntity connection = this.connectionEntity(CONNECTION_AB, OUTPUT_A, INPUT_B);
        when(this.connectionRepository.findAllById(any())).thenReturn(List.of(connection));
        when(this.portRepository.findAllById(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        this.portEntity(OUTPUT_A, WORKFLOW_ID, NODE_A, "OUTPUT", "Output", "Output.", 0),
                        this.portEntity(INPUT_B, WORKFLOW_ID, NODE_B, "INPUT", "Input", "Input.", 0)
                ));

        this.repository.save(this.workflow(List.of(this.nodeA(), this.nodeB()), List.of(new WorkflowConnection(CONNECTION_AB, OUTPUT_B, INPUT_A))));

        verify(this.connectionRepository).findAllById(Set.of(CONNECTION_AB));
        verify(this.portRepository).findAllById(Set.of(OUTPUT_A, INPUT_B));
        assertThat(this.savedConnections())
                .extracting(WorkflowConnectionEntity::getId, WorkflowConnectionEntity::getSourceOutputPortId, WorkflowConnectionEntity::getTargetInputPortId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CONNECTION_AB, OUTPUT_B, INPUT_A));
    }

    @Test
    void existingConnectionUuidOwnedByAnotherWorkflowIsRejectedBeforeChildMutation() {
        final WorkflowConnectionEntity connection = this.connectionEntity(CONNECTION_AB, OUTPUT_A, INPUT_B);
        when(this.connectionRepository.findAllById(any())).thenReturn(List.of(connection));
        when(this.portRepository.findAllById(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        this.portEntity(OUTPUT_A, OTHER_WORKFLOW_ID, NODE_A, "OUTPUT", "Output", "Output.", 0),
                        this.portEntity(INPUT_B, OTHER_WORKFLOW_ID, NODE_B, "INPUT", "Input", "Input.", 0)
                ));

        assertThatThrownBy(() -> this.repository.save(this.workflow(List.of(this.nodeA(), this.nodeB()), List.of(new WorkflowConnection(CONNECTION_AB, OUTPUT_B, INPUT_A)))))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_CONNECTION_ID_IN_USE");

        verify(this.connectionRepository).findAllById(Set.of(CONNECTION_AB));
        verify(this.portRepository).findAllById(Set.of(OUTPUT_A, INPUT_B));
        verify(this.nodeRepository, never()).findByWorkflowId(WORKFLOW_ID);
        verify(this.nodeRepository, never()).saveAll(any());
        verify(this.portRepository, never()).saveAll(any());
        verify(this.connectionRepository, never()).saveAll(any());
    }

    private Workflow workflow(final List<Node> nodes, final List<WorkflowConnection> connections) {
        return this.workflow(WORKFLOW_ID, nodes, connections);
    }

    private Workflow workflow(final UUID workflowId, final List<Node> nodes, final List<WorkflowConnection> connections) {
        return new Workflow(workflowId, PROJECT_ID, "Full Testing", "full testing", nodes, connections, null, NOW, NOW);
    }

    private Node nodeA() {
        return new Node(NODE_A, AGENT_ID, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(INPUT_A, "Input", "Input.", 0)),
                List.of(new NodePort(OUTPUT_A, "Output", "Output.", 0)),
                new NodePosition(1.0, 2.0));
    }

    private Node nodeB() {
        return new Node(NODE_B, AGENT_ID, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(INPUT_B, "Input", "Input.", 0)),
                List.of(new NodePort(OUTPUT_B, "Output", "Output.", 0)),
                new NodePosition(3.0, 4.0));
    }

    private WorkflowNodeEntity nodeEntity(final UUID id) {
        final WorkflowNodeEntity entity = new WorkflowNodeEntity();
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setId(id);
        entity.setTargetId(AGENT_ID);
        entity.setInputMode(NodeInputMode.DEPENDENCIES_ONLY.name());
        entity.setPositionX(1.0);
        entity.setPositionY(2.0);
        return entity;
    }

    private WorkflowNodePortEntity portEntity(final UUID id,
                                              final UUID workflowId,
                                              final UUID nodeId,
                                              final String direction,
                                              final String name,
                                              final String description,
                                              final int order) {
        final WorkflowNodePortEntity entity = this.portEntity(id, nodeId, direction, name, description, order);
        entity.setWorkflowId(workflowId);
        return entity;
    }

    private WorkflowNodePortEntity portEntity(final UUID id,
                                              final UUID nodeId,
                                              final String direction,
                                              final String name,
                                              final String description,
                                              final int order) {
        final WorkflowNodePortEntity entity = new WorkflowNodePortEntity();
        entity.setId(id);
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setNodeId(nodeId);
        entity.setDirection(direction);
        entity.setName(name);
        entity.setDescription(description);
        entity.setPortOrder(order);
        return entity;
    }

    private WorkflowConnectionEntity connectionEntity(final UUID id, final UUID sourceOutputPortId, final UUID targetInputPortId) {
        final WorkflowConnectionEntity entity = new WorkflowConnectionEntity();
        entity.setId(id);
        entity.setSourceOutputPortId(sourceOutputPortId);
        entity.setTargetInputPortId(targetInputPortId);
        return entity;
    }

    private List<WorkflowNodeEntity> savedNodes() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<WorkflowNodeEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(this.nodeRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }

    private List<WorkflowNodePortEntity> savedPorts() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<WorkflowNodePortEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(this.portRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }

    private List<WorkflowConnectionEntity> savedConnections() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<WorkflowConnectionEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(this.connectionRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }
}
