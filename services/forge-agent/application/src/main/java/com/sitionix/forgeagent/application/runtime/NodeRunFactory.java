package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NodeRunFactory {

    private final Clock clock;
    private final ScopeProjectionPolicy scopeProjectionPolicy;
    private final WorkflowRunGraphRepository graphRepository;

    public NodeRun root(final WorkflowRun workflowRun,
                        final ExecutionFrame executionFrame,
                        final RunNode runNode,
                        final UUID enteredViaInputPortId, final UUID repositoryId) {
        return this.create(workflowRun, executionFrame, runNode, null, enteredViaInputPortId, repositoryId, List.of());
    }

    public NodeRun activated(final WorkflowRun workflowRun,
                             final ExecutionFrame executionFrame,
                             final ExecutionFrame activationFrame,
                             final RunNode runNode,
                             final UUID enteredViaInputPortId, final UUID repositoryId) {
        return this.create(workflowRun, executionFrame, runNode, activationFrame.id(), enteredViaInputPortId, repositoryId, List.of());
    }

    public NodeRun activated(final WorkflowRun workflowRun, final ExecutionFrame executionFrame,
                             final ExecutionFrame activationFrame, final RunNode runNode,
                             final UUID enteredViaInputPortId, final UUID repositoryId,
                             final List<NodeRun> incoming) {
        return this.create(workflowRun, executionFrame, runNode, activationFrame.id(), enteredViaInputPortId,
                repositoryId, incoming);
    }

    UUID iteration(final WorkflowRun workflowRun, final RunNode target,
                          final UUID repositoryId, final List<NodeRun> incoming) {
        if (!target.contextMode().iterationScoped()) {
            return null;
        }
        final boolean mixed = this.isMixedIterationGroup(workflowRun, target);
        final var identities = incoming.stream()
                .filter(source -> workflowRun.id().equals(source.workflowRunId()))
                .filter(source -> target.contextGroupKey().equals(source.contextGroupKey()))
                .filter(source -> mixed || Objects.equals(repositoryId, source.repositoryId()))
                .map(NodeRun::contextIterationId).filter(Objects::nonNull).distinct().toList();
        if (identities.size() > 1) {
            throw new ConflictException(
                    "AGENT_CONTEXT_ITERATION_CONFLICT", "Incoming contributions belong to different context iterations.");
        }
        if (!identities.isEmpty()) return identities.getFirst();
        return mixed ? mixedEntryIdentity(workflowRun, target, incoming) : UUID.randomUUID();
    }

    private boolean isMixedIterationGroup(final WorkflowRun run, final RunNode target) {
        if (target.contextMode() != NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION) return false;
        final var graph = run.runtimeGraph() == null
                ? this.graphRepository.findByWorkflowRunId(run.id()) : run.runtimeGraph();
        return graph != null && graph.nodes().stream()
                .filter(node -> target.contextGroupKey().equals(node.contextGroupKey()))
                .map(RunNode::scopeMode).distinct().count() > 1;
    }

    private static UUID mixedEntryIdentity(final WorkflowRun run, final RunNode target, final List<NodeRun> incoming) {
        // A task entry is owned by the persisted run; a projected entry is owned by
        // its delivered source invocations. Both survive replay, unlike a random
        // UUID allocated separately for each repository. Frames are not identity.
        final var sources = incoming.stream().map(NodeRun::id).distinct().sorted().toList();
        final String group = target.contextGroupKey();
        final String entry = "mixed-iteration:" + run.id() + ":" + group.length() + ":" + group + ":" + sources;
        return UUID.nameUUIDFromBytes(entry.getBytes(StandardCharsets.UTF_8));
    }

    private NodeRun create(final WorkflowRun workflowRun,
                           final ExecutionFrame executionFrame,
                           final RunNode runNode,
                           final UUID activationFrameId,
                           final UUID enteredViaInputPortId, final UUID repositoryId, final List<NodeRun> incoming) {
        this.scopeProjectionPolicy.assertValidSourceInvocation(
                runNode.scopeMode(), repositoryId, workflowRun.repositoryIds());
        return new NodeRun(
                UUID.randomUUID(),
                workflowRun.id(),
                runNode.sourceNodeId(),
                runNode.sourceAgentId(),
                runNode.agentName(),
                runNode.agentInstructions(),
                runNode.agentOutputSchema(),
                runNode.inputMode(),
                runNode.position(),
                executionFrame.id(),
                enteredViaInputPortId,
                activationFrameId,
                null,
                null,
                NodeRunStatus.PENDING,
                null,
                null,
                runNode.executionModel(),
                Instant.now(this.clock),
                null,
                null,
                repositoryId,
                runNode.contextMode(),
                1,
                null,
                runNode.contextGroupKey(),
                iteration(workflowRun, runNode, repositoryId, incoming)
        );
    }

}
