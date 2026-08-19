package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunConnectionEntityId;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunNodeEntityId;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunPortEntityId;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunNodeRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunPortRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataWorkflowRunRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresWorkflowRunGraphRepository implements WorkflowRunGraphRepository {

    private final SpringDataWorkflowRunNodeRepository nodeRepository;
    private final SpringDataWorkflowRunPortRepository portRepository;
    private final SpringDataWorkflowRunConnectionRepository connectionRepository;
    private final SpringDataWorkflowRunRepository workflowRunRepository;

    @Override
    public void saveSnapshot(final WorkflowRunGraph graph) {
        this.nodeRepository.saveAll(graph.nodes().stream().map(this::toEntity).toList());
        this.portRepository.saveAll(graph.ports().stream().map(this::toEntity).toList());
        this.connectionRepository.saveAll(graph.connections().stream().map(this::toEntity).toList());
    }

    @Override
    public WorkflowRunGraph findByWorkflowRunId(final UUID workflowRunId) {
        return new WorkflowRunGraph(
                workflowRunId,
                this.workflowRunRepository.findById(workflowRunId)
                        .map(com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity::getTaskInputPortId)
                        .orElse(null),
                this.workflowRunRepository.findById(workflowRunId)
                        .map(com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity::getTaskOutputPortId)
                        .orElse(null),
                this.nodeRepository.findByWorkflowRunIdOrderBySourceNodeIdAsc(workflowRunId).stream().map(this::toDomain).toList(),
                this.portRepository.findByWorkflowRunIdOrderBySourceNodeIdAscPortOrderAsc(workflowRunId).stream().map(this::toDomain).toList(),
                this.connectionRepository.findByWorkflowRunIdOrderBySourceConnectionIdAsc(workflowRunId).stream().map(this::toDomain).toList()
        );
    }

    @Override
    public Optional<RunNode> findNode(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.nodeRepository.findById(new WorkflowRunNodeEntityId(workflowRunId, sourceNodeId)).map(this::toDomain);
    }

    @Override
    public Optional<RunPort> findPort(final UUID workflowRunId, final UUID sourcePortId) {
        return this.portRepository.findById(new WorkflowRunPortEntityId(workflowRunId, sourcePortId)).map(this::toDomain);
    }

    @Override
    public List<RunPort> findOutputPortsByNode(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.portRepository.findByWorkflowRunIdAndSourceNodeIdAndDirectionOrderByPortOrderAsc(workflowRunId, sourceNodeId, PortDirection.OUTPUT.name()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<RunConnection> findConnectionsBySourceOutputPorts(final UUID workflowRunId, final Collection<UUID> sourceOutputPortIds) {
        if (sourceOutputPortIds == null || sourceOutputPortIds.isEmpty()) {
            return List.of();
        }
        return this.connectionRepository.findByWorkflowRunIdAndSourceOutputPortIdInOrderBySourceConnectionIdAsc(workflowRunId, sourceOutputPortIds).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<RunConnection> findIncomingConnections(final UUID workflowRunId, final UUID targetInputPortId) {
        return this.connectionRepository.findByWorkflowRunIdAndTargetInputPortIdOrderBySourceConnectionIdAsc(workflowRunId, targetInputPortId).stream()
                .map(this::toDomain)
                .toList();
    }

    private RunNode toDomain(final WorkflowRunNodeEntity entity) {
        return new RunNode(
                entity.getWorkflowRunId(),
                entity.getSourceNodeId(),
                entity.getSourceAgentId(),
                entity.getAgentName(),
                entity.getAgentInstructions(),
                AgentOutputSchema.ofCanonicalJsonObject(entity.getAgentOutputSchema()),
                new NodeRunExecutionModel(entity.getExecutionModelProviderId(), entity.getExecutionModelId(), entity.getExecutionModelEffortId()),
                NodeInputMode.valueOf(entity.getInputMode()),
                new NodePosition(entity.getPositionX(), entity.getPositionY()),
                NodeScopeMode.valueOf(entity.getScopeMode())
        );
    }

    private RunPort toDomain(final WorkflowRunPortEntity entity) {
        return new RunPort(
                entity.getWorkflowRunId(),
                entity.getSourcePortId(),
                entity.getSourceNodeId(),
                PortDirection.valueOf(entity.getDirection()),
                entity.getName(),
                entity.getDescription(),
                entity.getPortOrder()
        );
    }

    private RunConnection toDomain(final WorkflowRunConnectionEntity entity) {
        return new RunConnection(
                entity.getWorkflowRunId(),
                entity.getSourceConnectionId(),
                entity.getSourceOutputPortId(),
                entity.getTargetInputPortId()
        );
    }

    private WorkflowRunNodeEntity toEntity(final RunNode node) {
        final WorkflowRunNodeEntity entity = new WorkflowRunNodeEntity();
        entity.setWorkflowRunId(node.workflowRunId());
        entity.setSourceNodeId(node.sourceNodeId());
        entity.setSourceAgentId(node.sourceAgentId());
        entity.setAgentName(node.agentName());
        entity.setAgentInstructions(node.agentInstructions());
        entity.setAgentOutputSchema(node.agentOutputSchema().jsonObject());
        entity.setExecutionModelProviderId(node.executionModel().providerId());
        entity.setExecutionModelId(node.executionModel().modelId());
        entity.setExecutionModelEffortId(node.executionModel().effortId());
        entity.setInputMode(node.inputMode().name());
        entity.setScopeMode(node.scopeMode().name());
        entity.setPositionX(node.position().x());
        entity.setPositionY(node.position().y());
        return entity;
    }

    private WorkflowRunPortEntity toEntity(final RunPort port) {
        final WorkflowRunPortEntity entity = new WorkflowRunPortEntity();
        entity.setWorkflowRunId(port.workflowRunId());
        entity.setSourcePortId(port.sourcePortId());
        entity.setSourceNodeId(port.sourceNodeId());
        entity.setDirection(port.direction().name());
        entity.setName(port.name());
        entity.setDescription(port.description());
        entity.setPortOrder(port.order());
        return entity;
    }

    private WorkflowRunConnectionEntity toEntity(final RunConnection connection) {
        final WorkflowRunConnectionEntity entity = new WorkflowRunConnectionEntity();
        entity.setWorkflowRunId(connection.workflowRunId());
        entity.setSourceConnectionId(connection.sourceConnectionId());
        entity.setSourceOutputPortId(connection.sourceOutputPortId());
        entity.setTargetInputPortId(connection.targetInputPortId());
        return entity;
    }
}
