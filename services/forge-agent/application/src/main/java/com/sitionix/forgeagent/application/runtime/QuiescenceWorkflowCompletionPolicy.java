package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuiescenceWorkflowCompletionPolicy implements WorkflowCompletionPolicy {

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunGraphRepository graphRepository;
    private final ExecutionFrameRepository frameRepository;
    private final InputActivationResolutionRepository activationResolutionRepository;
    private final InputParticipationResolver inputParticipationResolver;
    private final WorkflowCompletionRuleRegistry ruleRegistry;

    @Override
    public WorkflowCompletionDecision evaluate(final WorkflowRun workflowRun) {
        final List<com.sitionix.forgeagent.domain.model.NodeRun> nodeRuns = this.nodeRunRepository.findByWorkflowRunId(workflowRun.id());
        return this.ruleRegistry.evaluate(new WorkflowCompletionContext(workflowRun, nodeRuns, this.hasOpenActivation(workflowRun)));
    }

    private boolean hasOpenActivation(final WorkflowRun workflowRun) {
        final WorkflowRunGraph graph = this.graphRepository.findByWorkflowRunId(workflowRun.id());
        final List<RunPort> inputPorts = graph.ports().stream()
                .filter(port -> port.direction() == PortDirection.INPUT)
                .toList();
        final List<UUID> frameIds = this.frameRepository.findByWorkflowRunId(workflowRun.id()).stream()
                .map(ExecutionFrame::id)
                .toList();
        for (final UUID frameId : frameIds) {
            for (final RunPort inputPort : inputPorts) {
                final List<RunConnection> incoming = graph.connections().stream()
                        .filter(connection -> connection.targetInputPortId().equals(inputPort.sourcePortId()))
                        .toList();
                if (incoming.isEmpty()) {
                    continue;
                }
                final NodeScopeMode scopeMode = graph.nodes().stream()
                        .filter(node -> node.sourceNodeId().equals(inputPort.sourceNodeId())).findFirst().orElseThrow().scopeMode();
                final List<UUID> repositories = scopeMode == NodeScopeMode.GLOBAL
                        ? java.util.Collections.singletonList(null) : workflowRun.repositoryIds();
                for (final UUID repositoryId : repositories) {
                    if (this.activationResolutionRepository.find(workflowRun.id(), frameId, inputPort.sourcePortId(), repositoryId).isPresent()) {
                        continue;
                    }
                    final InputParticipation participation = this.inputParticipationResolver.resolve(workflowRun.id(), frameId, inputPort.sourcePortId(), repositoryId);
                    if (participation.open() || !participation.delivered().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
