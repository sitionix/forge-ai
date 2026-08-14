package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodePortEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodePortRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresWorkflowRepository implements WorkflowRepository {

    private final SpringDataWorkflowRepository workflowRepository;
    private final SpringDataWorkflowNodeRepository nodeRepository;
    private final SpringDataWorkflowNodePortRepository portRepository;

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
        this.reconcileNodes(workflow);
        return this.toDomain(savedWorkflow);
    }

    @Override
    public void deleteById(final UUID workflowId) {
        this.workflowRepository.deleteById(workflowId);
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
            entity.setDependsOnNodeIds(node.dependsOnNodeIds().toArray(UUID[]::new));
            entity.setInputMode(inputMode(node.inputMode()).name());
            entity.setPositionX(node.position().x());
            entity.setPositionY(node.position().y());
            desiredEntities.add(entity);
        }
        this.nodeRepository.saveAll(desiredEntities);
        this.reconcilePorts(workflow);
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
                entity.getDependsOnNodeIds() == null ? List.of() : Arrays.asList(entity.getDependsOnNodeIds()),
                inputMode(entity.getInputMode()),
                this.toPorts(inputPortsByNode.getOrDefault(entity.getId(), List.of())),
                this.toPorts(outputPortsByNode.getOrDefault(entity.getId(), List.of())),
                new NodePosition(entity.getPositionX(), entity.getPositionY())
        );
    }

    private List<NodePort> toPorts(final List<WorkflowNodePortEntity> entities) {
        return entities.stream()
                .sorted(Comparator.comparingInt(WorkflowNodePortEntity::getPortOrder))
                .map(entity -> new NodePort(entity.getId(), entity.getName(), entity.getDescription(), entity.getPortOrder()))
                .toList();
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

    private WorkflowEntity toEntity(final Workflow workflow) {
        final WorkflowEntity entity = new WorkflowEntity();
        entity.setId(workflow.id());
        entity.setProjectId(workflow.projectId());
        entity.setName(workflow.name());
        entity.setNormalizedName(workflow.normalizedName());
        entity.setCreatedAt(workflow.createdAt());
        entity.setUpdatedAt(workflow.updatedAt());
        return entity;
    }
}
