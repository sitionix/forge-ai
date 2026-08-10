package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRepository;
import java.util.ArrayList;
import java.util.Arrays;
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

    private void reconcileNodes(final Workflow workflow) {
        final Map<UUID, WorkflowNodeEntity> currentById = this.nodeRepository.findByWorkflowId(workflow.id()).stream()
                .collect(Collectors.toMap(WorkflowNodeEntity::getId, Function.identity()));
        final Set<UUID> desiredIds = workflow.nodes().stream().map(Node::id).collect(Collectors.toSet());
        final List<WorkflowNodeEntity> removed = currentById.values().stream()
                .filter(node -> !desiredIds.contains(node.getId()))
                .toList();
        if (!removed.isEmpty()) {
            this.nodeRepository.deleteAll(removed);
        }
        final List<WorkflowNodeEntity> desiredEntities = new ArrayList<>();
        for (final Node node : workflow.nodes()) {
            final WorkflowNodeEntity entity = currentById.getOrDefault(node.id(), new WorkflowNodeEntity());
            entity.setId(node.id());
            entity.setWorkflowId(workflow.id());
            entity.setTargetId(node.targetId());
            entity.setDependsOnNodeIds(node.dependsOnNodeIds().toArray(UUID[]::new));
            entity.setPositionX(node.position().x());
            entity.setPositionY(node.position().y());
            desiredEntities.add(entity);
        }
        this.nodeRepository.saveAll(desiredEntities);
    }

    private Workflow toDomain(final WorkflowEntity entity) {
        return new Workflow(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getNormalizedName(),
                this.nodeRepository.findByWorkflowIdOrderByIdAsc(entity.getId()).stream()
                        .map(this::toDomain)
                        .toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private Node toDomain(final WorkflowNodeEntity entity) {
        return new Node(
                entity.getId(),
                entity.getTargetId(),
                entity.getDependsOnNodeIds() == null ? List.of() : Arrays.asList(entity.getDependsOnNodeIds()),
                new NodePosition(entity.getPositionX(), entity.getPositionY())
        );
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
