package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodePortEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodePortRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresWorkflowRepository implements WorkflowRepository {

    private final SpringDataWorkflowRepository workflowRepository;
    private final SpringDataWorkflowNodeRepository nodeRepository;
    private final SpringDataWorkflowNodePortRepository portRepository;
    private final SpringDataWorkflowConnectionRepository connectionRepository;

    @Override
    public List<Workflow> findByProjectId(final UUID projectId) {
        return this.workflowRepository.findByProjectIdOrderByNormalizedNameAscIdAsc(projectId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Workflow> findById(final UUID workflowId) {
        return this.workflowRepository.findById(workflowId).map(this::toDomain);
    }

    @Override
    public Optional<Workflow> findByIdForUpdate(final UUID workflowId) {
        return this.workflowRepository.findByIdForUpdate(workflowId).map(this::toDomain);
    }

    @Override
    public boolean existsByProjectIdAndNormalizedName(final UUID projectId, final String normalizedName) {
        return this.workflowRepository.existsByProjectIdAndNormalizedName(projectId, normalizedName);
    }

    @Override
    public boolean existsByProjectIdAndNormalizedNameExcludingId(final UUID projectId,
                                                                final String normalizedName,
                                                                final UUID excludedWorkflowId) {
        return this.workflowRepository.existsByProjectIdAndNormalizedNameAndIdNot(projectId, normalizedName, excludedWorkflowId);
    }

    @Override
    public Workflow save(final Workflow workflow) {
        final WorkflowEntity savedWorkflow = this.workflowRepository.save(this.toEntity(workflow));
        this.validatePersistedIdentityOwnership(workflow);
        this.reconcileNodes(workflow);
        return this.toDomain(savedWorkflow);
    }

    @Override
    public void deleteById(final UUID workflowId) {
        this.workflowRepository.deleteById(workflowId);
    }

    private void validatePersistedIdentityOwnership(final Workflow workflow) {
        this.validatePersistedPortOwnership(workflow);
        this.validatePersistedConnectionOwnership(workflow);
    }

    private void validatePersistedPortOwnership(final Workflow workflow) {
        final Set<UUID> requestedPortIds = this.workflowPortIds(workflow);
        if (requestedPortIds.isEmpty()) {
            return;
        }
        for (final WorkflowNodePortEntity existing : this.portRepository.findAllById(requestedPortIds)) {
            if (!workflow.id().equals(existing.getWorkflowId())) {
                throw new ConflictException(
                        "WORKFLOW_NODE_PORT_ID_IN_USE",
                        "Workflow node port ID is already owned by another workflow."
                );
            }
        }
    }

    private void validatePersistedConnectionOwnership(final Workflow workflow) {
        final Set<UUID> requestedConnectionIds = connectionsOrEmpty(workflow.connections()).stream()
                .map(WorkflowConnection::id)
                .collect(Collectors.toSet());
        if (requestedConnectionIds.isEmpty()) {
            return;
        }
        final List<WorkflowConnectionEntity> existingConnections = StreamSupport.stream(
                this.connectionRepository.findAllById(requestedConnectionIds).spliterator(),
                false
        ).toList();
        if (existingConnections.isEmpty()) {
            return;
        }
        final Set<UUID> endpointPortIds = existingConnections.stream()
                .flatMap(connection -> List.of(connection.getSourceOutputPortId(), connection.getTargetInputPortId()).stream())
                .collect(Collectors.toSet());
        final Map<UUID, WorkflowNodePortEntity> portsById = StreamSupport.stream(
                this.portRepository.findAllById(endpointPortIds).spliterator(),
                false
        ).collect(Collectors.toMap(WorkflowNodePortEntity::getId, Function.identity()));
        for (final WorkflowConnectionEntity existing : existingConnections) {
            final WorkflowNodePortEntity source = portsById.get(existing.getSourceOutputPortId());
            final WorkflowNodePortEntity target = portsById.get(existing.getTargetInputPortId());
            if (source == null
                    || target == null
                    || !workflow.id().equals(source.getWorkflowId())
                    || !workflow.id().equals(target.getWorkflowId())) {
                throw new ConflictException(
                        "WORKFLOW_CONNECTION_ID_IN_USE",
                        "Workflow connection ID is already owned by another workflow."
                );
            }
        }
    }

    private void reconcileNodes(final Workflow workflow) {
        final Map<UUID, WorkflowNodeEntity> currentById = this.nodeRepository.findByWorkflowId(workflow.id()).stream()
                .collect(Collectors.toMap(WorkflowNodeEntity::getId, Function.identity()));
        final Set<UUID> desiredIds = workflow.nodes().stream().map(Node::id).collect(Collectors.toSet());
        final List<WorkflowNodeEntity> removed = currentById.values().stream()
                .filter(node -> !desiredIds.contains(node.getId()))
                .toList();
        if (!removed.isEmpty()) {
            this.nodeRepository.deleteAll(removed);
            this.nodeRepository.flush();
        }
        final List<WorkflowNodeEntity> desiredEntities = new ArrayList<>();
        for (final Node node : workflow.nodes()) {
            final WorkflowNodeEntity entity = currentById.getOrDefault(node.id(), new WorkflowNodeEntity());
            entity.setId(node.id());
            entity.setWorkflowId(workflow.id());
            entity.setTargetId(node.targetId());
            entity.setInputMode(inputMode(node.inputMode()).name());
            entity.setScopeMode(node.scopeMode().name());
            entity.setPositionX(node.position().x());
            entity.setPositionY(node.position().y());
            desiredEntities.add(entity);
        }
        this.nodeRepository.saveAll(desiredEntities);
        this.reconcilePorts(workflow);
        this.reconcileConnections(workflow);
    }

    private void reconcilePorts(final Workflow workflow) {
        final Map<UUID, WorkflowNodePortEntity> currentById = this.portRepository.findByWorkflowId(workflow.id()).stream()
                .collect(Collectors.toMap(WorkflowNodePortEntity::getId, Function.identity()));
        final Set<UUID> desiredIds = workflow.nodes().stream()
                .flatMap(node -> List.of(portsOrEmpty(node.inputs()), portsOrEmpty(node.outputs())).stream())
                .flatMap(List::stream)
                .map(NodePort::id)
                .collect(Collectors.toSet());
        final List<WorkflowNodePortEntity> removed = currentById.values().stream()
                .filter(port -> !desiredIds.contains(port.getId()))
                .toList();
        if (!removed.isEmpty()) {
            this.portRepository.deleteAll(removed);
            this.portRepository.flush();
        }
        final List<WorkflowNodePortEntity> desiredEntities = new ArrayList<>();
        for (final Node node : workflow.nodes()) {
            this.addPortEntities(workflow.id(), node.id(), "INPUT", portsOrEmpty(node.inputs()), currentById, desiredEntities);
            this.addPortEntities(workflow.id(), node.id(), "OUTPUT", portsOrEmpty(node.outputs()), currentById, desiredEntities);
        }
        this.portRepository.saveAll(desiredEntities);
    }

    private void reconcileConnections(final Workflow workflow) {
        final Set<UUID> workflowPortIds = this.workflowPortIds(workflow);
        final Map<UUID, WorkflowConnectionEntity> currentById = this.findConnectionsByWorkflowPortIds(workflowPortIds).stream()
                .collect(Collectors.toMap(WorkflowConnectionEntity::getId, Function.identity()));
        final Set<UUID> desiredIds = connectionsOrEmpty(workflow.connections()).stream()
                .map(WorkflowConnection::id)
                .collect(Collectors.toSet());
        final List<WorkflowConnectionEntity> removed = currentById.values().stream()
                .filter(connection -> !desiredIds.contains(connection.getId()))
                .toList();
        if (!removed.isEmpty()) {
            this.connectionRepository.deleteAll(removed);
            this.connectionRepository.flush();
        }
        final List<WorkflowConnectionEntity> desiredEntities = new ArrayList<>();
        for (final WorkflowConnection connection : connectionsOrEmpty(workflow.connections())) {
            final WorkflowConnectionEntity entity = currentById.getOrDefault(connection.id(), new WorkflowConnectionEntity());
            entity.setId(connection.id());
            entity.setSourceOutputPortId(connection.sourceOutputPortId());
            entity.setTargetInputPortId(connection.targetInputPortId());
            desiredEntities.add(entity);
        }
        this.connectionRepository.saveAll(desiredEntities);
    }

    private void addPortEntities(final UUID workflowId,
                                 final UUID nodeId,
                                 final String direction,
                                 final List<NodePort> ports,
                                 final Map<UUID, WorkflowNodePortEntity> currentById,
                                 final List<WorkflowNodePortEntity> desiredEntities) {
        for (final NodePort port : ports) {
            final WorkflowNodePortEntity entity = currentById.getOrDefault(port.id(), new WorkflowNodePortEntity());
            entity.setId(port.id());
            entity.setWorkflowId(workflowId);
            entity.setNodeId(nodeId);
            entity.setDirection(direction);
            entity.setName(port.name());
            entity.setDescription(port.description());
            entity.setPortOrder(port.order());
            desiredEntities.add(entity);
        }
    }

    private Workflow toDomain(final WorkflowEntity entity) {
        final List<WorkflowNodePortEntity> ports = this.portRepository.findByWorkflowIdOrderByNodeIdAscPortOrderAsc(entity.getId());
        final Set<UUID> workflowPortIds = ports.stream().map(WorkflowNodePortEntity::getId).collect(Collectors.toSet());
        final Map<UUID, List<WorkflowNodePortEntity>> inputPortsByNode = ports.stream()
                .filter(port -> "INPUT".equals(port.getDirection()))
                .collect(Collectors.groupingBy(WorkflowNodePortEntity::getNodeId));
        final Map<UUID, List<WorkflowNodePortEntity>> outputPortsByNode = ports.stream()
                .filter(port -> "OUTPUT".equals(port.getDirection()))
                .collect(Collectors.groupingBy(WorkflowNodePortEntity::getNodeId));
        return new Workflow(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getNormalizedName(),
                this.nodeRepository.findByWorkflowIdOrderByIdAsc(entity.getId()).stream()
                        .map(node -> this.toDomain(node, inputPortsByNode, outputPortsByNode))
                        .toList(),
                this.findConnectionsByWorkflowPortIds(workflowPortIds).stream()
                        .sorted(Comparator.comparing(WorkflowConnectionEntity::getId))
                        .map(this::toDomain)
                        .toList(),
                entity.getTaskInputPortId(),
                entity.getTaskOutputPortId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private Node toDomain(final WorkflowNodeEntity entity,
                          final Map<UUID, List<WorkflowNodePortEntity>> inputPortsByNode,
                          final Map<UUID, List<WorkflowNodePortEntity>> outputPortsByNode) {
        return new Node(
                entity.getId(),
                entity.getTargetId(),
                inputMode(entity.getInputMode()),
                this.toPorts(inputPortsByNode.getOrDefault(entity.getId(), List.of())),
                this.toPorts(outputPortsByNode.getOrDefault(entity.getId(), List.of())),
                new NodePosition(entity.getPositionX(), entity.getPositionY()),
                NodeScopeMode.valueOf(entity.getScopeMode())
        );
    }

    private List<NodePort> toPorts(final List<WorkflowNodePortEntity> entities) {
        return entities.stream()
                .sorted(Comparator.comparingInt(WorkflowNodePortEntity::getPortOrder))
                .map(entity -> new NodePort(entity.getId(), entity.getName(), entity.getDescription(), entity.getPortOrder()))
                .toList();
    }

    private WorkflowConnection toDomain(final WorkflowConnectionEntity entity) {
        return new WorkflowConnection(entity.getId(), entity.getSourceOutputPortId(), entity.getTargetInputPortId());
    }

    private static NodeInputMode inputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.DEPENDENCIES_ONLY : inputMode;
    }

    private static NodeInputMode inputMode(final String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.DEPENDENCIES_ONLY;
        }
        return NodeInputMode.valueOf(inputMode);
    }

    private static List<NodePort> portsOrEmpty(final List<NodePort> ports) {
        return ports == null ? List.of() : ports;
    }

    private static List<WorkflowConnection> connectionsOrEmpty(final List<WorkflowConnection> connections) {
        return connections == null ? List.of() : connections;
    }

    private Set<UUID> workflowPortIds(final Workflow workflow) {
        return workflow.nodes().stream()
                .flatMap(node -> List.of(portsOrEmpty(node.inputs()), portsOrEmpty(node.outputs())).stream())
                .flatMap(List::stream)
                .map(NodePort::id)
                .collect(Collectors.toSet());
    }

    private List<WorkflowConnectionEntity> findConnectionsByWorkflowPortIds(final Set<UUID> workflowPortIds) {
        if (workflowPortIds.isEmpty()) {
            return List.of();
        }
        return this.connectionRepository.findBySourceOutputPortIdIn(workflowPortIds).stream()
                .filter(connection -> workflowPortIds.contains(connection.getTargetInputPortId()))
                .toList();
    }

    private WorkflowEntity toEntity(final Workflow workflow) {
        final WorkflowEntity entity = new WorkflowEntity();
        entity.setId(workflow.id());
        entity.setProjectId(workflow.projectId());
        entity.setName(workflow.name());
        entity.setNormalizedName(workflow.normalizedName());
        entity.setTaskInputPortId(workflow.taskInputPortId());
        entity.setTaskOutputPortId(workflow.taskOutputPortId());
        entity.setCreatedAt(workflow.createdAt());
        entity.setUpdatedAt(workflow.updatedAt());
        return entity;
    }
}
