package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowRunUseCases {

    private final WorkflowRepository workflowRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final Clock clock;

    @Transactional
    public WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command) {
        return this.createWorkflowRun(workflowId, command, null);
    }

    @Transactional
    public WorkflowRun createWorkflowRunForTask(final UUID workflowId, final CreateWorkflowRunCommand command, final UUID taskId) {
        if (taskId == null) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_TASK", "Workflow run taskId is required.");
        }
        return this.createWorkflowRun(workflowId, command, taskId);
    }

    private WorkflowRun createWorkflowRun(final UUID workflowId, final CreateWorkflowRunCommand command, final UUID taskId) {
        final String input = this.requireInput(command == null ? null : command.input());
        final Workflow identity = this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        this.workflowRepository.findByIdForUpdate(identity.id())
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        final Workflow workflow = this.workflowRepository.findById(identity.id())
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        if (workflow.nodes().isEmpty()) {
            throw new ConflictException("EMPTY_WORKFLOW", "Workflow must contain at least one node before a run can be created.");
        }

        final Map<UUID, AgentDefinition> agentsById = this.loadSnapshotAgents(workflow);
        final Map<UUID, UUID> nodeRunIdsBySourceNodeId = this.generateNodeRunIds(workflow.nodes());
        final Instant now = Instant.now(this.clock);
        final UUID workflowRunId = UUID.randomUUID();
        final WorkflowRun run = new WorkflowRun(
                workflowRunId,
                workflow.projectId(),
                workflow.id(),
                taskId,
                workflow.name(),
                input,
                WorkflowRunStatus.QUEUED,
                workflow.nodes().stream()
                        .map(node -> this.toNodeRun(workflowRunId, workflow, node, agentsById, nodeRunIdsBySourceNodeId, now))
                        .toList(),
                now,
                null,
                null
        );
        return this.workflowRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRunSummary> listWorkflowRuns(final UUID workflowId) {
        this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        return this.workflowRunRepository.findSummariesBySourceWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public WorkflowRun getWorkflowRun(final UUID runId) {
        return this.workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
    }

    private Map<UUID, AgentDefinition> loadSnapshotAgents(final Workflow workflow) {
        final Set<UUID> targetIds = workflow.nodes().stream()
                .map(Node::targetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Map<UUID, AgentDefinition> agentsById = this.agentDefinitionRepository.findByIds(targetIds).stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        for (final UUID targetId : targetIds) {
            final AgentDefinition agent = agentsById.get(targetId);
            if (agent == null) {
                throw this.invalidSnapshot("Workflow node references an agent definition that does not exist.");
            }
            if (!workflow.projectId().equals(agent.projectId())) {
                throw this.invalidSnapshot("Workflow node references an agent definition from another project.");
            }
        }
        return agentsById;
    }

    private Map<UUID, UUID> generateNodeRunIds(final List<Node> nodes) {
        final Map<UUID, UUID> result = new LinkedHashMap<>();
        for (final Node node : nodes) {
            result.put(node.id(), UUID.randomUUID());
        }
        return result;
    }

    private NodeRun toNodeRun(final UUID workflowRunId,
                              final Workflow workflow,
                              final Node node,
                              final Map<UUID, AgentDefinition> agentsById,
                              final Map<UUID, UUID> nodeRunIdsBySourceNodeId,
                              final Instant now) {
        final AgentDefinition agent = agentsById.get(node.targetId());
        final List<UUID> dependsOnNodeRunIds = node.dependsOnNodeIds().stream()
                .map(sourceDependencyId -> this.requireNodeRunId(workflow, node, sourceDependencyId, nodeRunIdsBySourceNodeId))
                .toList();
        return new NodeRun(
                nodeRunIdsBySourceNodeId.get(node.id()),
                workflowRunId,
                node.id(),
                agent.id(),
                agent.name(),
                agent.instructions(),
                agent.outputSchema(),
                dependsOnNodeRunIds,
                node.position(),
                NodeRunStatus.PENDING,
                null,
                null,
                null,
                now,
                null,
                null
        );
    }

    private UUID requireNodeRunId(final Workflow workflow,
                                  final Node node,
                                  final UUID sourceDependencyId,
                                  final Map<UUID, UUID> nodeRunIdsBySourceNodeId) {
        final UUID nodeRunId = nodeRunIdsBySourceNodeId.get(sourceDependencyId);
        if (nodeRunId == null) {
            throw this.invalidSnapshot("Workflow node contains an invalid persisted dependency reference.");
        }
        if (node.id().equals(sourceDependencyId)) {
            throw this.invalidSnapshot("Workflow node cannot depend on itself.");
        }
        if (!workflow.nodes().stream().anyMatch(sourceNode -> sourceNode.id().equals(node.id()))) {
            throw this.invalidSnapshot("Workflow node is not owned by the source workflow.");
        }
        return nodeRunId;
    }

    private String requireInput(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_WORKFLOW_RUN_INPUT", "Workflow run input is required.");
        }
        return candidate.trim();
    }

    private ConflictException invalidSnapshot(final String message) {
        return new ConflictException("WORKFLOW_RUN_SNAPSHOT_INVALID", message);
    }
}
