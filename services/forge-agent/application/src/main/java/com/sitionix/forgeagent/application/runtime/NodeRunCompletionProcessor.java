package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ForgeAgentException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NodeRunCompletionProcessor {

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunGraphRepository graphRepository;
    private final OutputRoutingPolicyRegistry outputRoutingPolicyRegistry;
    private final NodeRunCompletionApplier applier;
    private final NodeRunCompletionPersistence completionPersistence;

    public void process(final UUID nodeRunId) {
        try {
            final Optional<NodeRunRoutingContext> context = this.prepare(nodeRunId);
            if (context.isEmpty()) {
                return;
            }
            final OutputRoutingDecision decision = this.outputRoutingPolicyRegistry.route(context.get().outputRoutingContext());
            this.applier.apply(nodeRunId, decision, context.get().outgoing());
        } catch (final ForgeAgentException exception) {
            this.completionPersistence.markCompletionFailed(nodeRunId, new NodeRunFailure(exception.code(), exception.getMessage()));
        } catch (final RuntimeException exception) {
            log.warn("Node run completion could not be applied and will remain retryable. nodeRunId={}", nodeRunId, exception);
        }
    }

    private Optional<NodeRunRoutingContext> prepare(final UUID nodeRunId) {
        final Optional<NodeRun> candidate = this.nodeRunRepository.findById(nodeRunId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        final NodeRun nodeRun = candidate.get();
        if (nodeRun.routingCompletedAt() != null || nodeRun.status() != NodeRunStatus.SUCCEEDED || nodeRun.executionFrameId() == null) {
            return Optional.empty();
        }
        final List<RunPort> outputs = this.graphRepository.findOutputPortsByNode(nodeRun.workflowRunId(), nodeRun.sourceNodeId());
        final List<RunConnection> outgoing = this.graphRepository.findConnectionsBySourceOutputPorts(
                nodeRun.workflowRunId(),
                outputs.stream().map(RunPort::sourcePortId).toList()
        );
        return Optional.of(new NodeRunRoutingContext(nodeRun, outputs, outgoing));
    }
}
